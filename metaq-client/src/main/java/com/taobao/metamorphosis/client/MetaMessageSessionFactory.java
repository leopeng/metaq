package com.taobao.metamorphosis.client;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import org.I0Itec.zkclient.ZkClient;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.taobao.diamond.manager.DiamondManager;
import com.taobao.diamond.manager.ManagerListener;
import com.taobao.diamond.manager.impl.DefaultDiamondManager;
import com.taobao.gecko.service.RemotingFactory;
import com.taobao.gecko.service.config.ClientConfig;
import com.taobao.gecko.service.exception.NotifyRemotingException;
import com.taobao.metamorphosis.client.consumer.ConsisHashStrategy;
import com.taobao.metamorphosis.client.consumer.ConsumerConfig;
import com.taobao.metamorphosis.client.consumer.ConsumerZooKeeper;
import com.taobao.metamorphosis.client.consumer.DefaultLoadBalanceStrategy;
import com.taobao.metamorphosis.client.consumer.LoadBalanceStrategy;
import com.taobao.metamorphosis.client.consumer.MessageConsumer;
import com.taobao.metamorphosis.client.consumer.RecoverManager;
import com.taobao.metamorphosis.client.consumer.RecoverStorageManager;
import com.taobao.metamorphosis.client.consumer.SimpleMessageConsumer;
import com.taobao.metamorphosis.client.consumer.SubscribeInfoManager;
import com.taobao.metamorphosis.client.consumer.storage.OffsetStorage;
import com.taobao.metamorphosis.client.consumer.storage.ZkOffsetStorage;
import com.taobao.metamorphosis.client.producer.MessageProducer;
import com.taobao.metamorphosis.client.producer.PartitionSelector;
import com.taobao.metamorphosis.client.producer.ProducerZooKeeper;
import com.taobao.metamorphosis.client.producer.RoundRobinPartitionSelector;
import com.taobao.metamorphosis.client.producer.SimpleMessageProducer;
import com.taobao.metamorphosis.exception.InvalidConsumerConfigException;
import com.taobao.metamorphosis.exception.InvalidOffsetStorageException;
import com.taobao.metamorphosis.exception.MetaClientException;
import com.taobao.metamorphosis.exception.NetworkException;
import com.taobao.metamorphosis.network.MetamorphosisWireFormatType;
import com.taobao.metamorphosis.utils.DiamondUtils;
import com.taobao.metamorphosis.utils.IdGenerator;
import com.taobao.metamorphosis.utils.MetaZookeeper;
import com.taobao.metamorphosis.utils.ZkUtils;
import com.taobao.metamorphosis.utils.ZkUtils.ZKConfig;


/**
 * 消息会话工厂，配置的优先级，优先使用传入的MetaClientConfig中的配置项，
 * 其次使用MetaClientConfig中的zkConfig配置的zk中的选项，如果都没有，则从diamond获取zk地址来获取配置项
 * 
 * @author boyan
 * @Date 2011-4-21
 * @author wuhua
 * @Date 2011-8-4
 */
public class MetaMessageSessionFactory implements MessageSessionFactory {
    public static long startTime = System.currentTimeMillis();// 记录客户端的启动时间
    protected RemotingClientWrapper remotingClient;
    private final MetaClientConfig metaClientConfig;
    private volatile ZkClient zkClient;

    static final Log log = LogFactory.getLog(MetaMessageSessionFactory.class);

    private final CopyOnWriteArrayList<ZkClientChangedListener> zkClientChangedListeners =
            new CopyOnWriteArrayList<ZkClientChangedListener>();

    protected final ProducerZooKeeper producerZooKeeper;

    private final ConsumerZooKeeper consumerZooKeeper;

    private DiamondManager diamondManager;
    private final CopyOnWriteArrayList<Shutdownable> children = new CopyOnWriteArrayList<Shutdownable>();
    private volatile boolean shutdown;
    private volatile boolean isHutdownHookCalled = false;
    private final Thread shutdownHook;
    private ZKConfig zkConfig;
    private final RecoverManager recoverManager;
    private final SubscribeInfoManager subscribeInfoManager;

    protected final IdGenerator sessionIdGenerator;

    protected MetaZookeeper metaZookeeper;


    /**
     * 返回通讯客户端
     * 
     * @return
     */
    public RemotingClientWrapper getRemotingClient() {
        return this.remotingClient;
    }


    /**
     * 返回订阅关系管理器
     * 
     * @return
     */
    public SubscribeInfoManager getSubscribeInfoManager() {
        return this.subscribeInfoManager;
    }


    /**
     * 返回客户端配置
     * 
     * @return
     */
    public MetaClientConfig getMetaClientConfig() {
        return this.metaClientConfig;
    }


    /**
     * 返回生产者和zk交互管理器
     * 
     * @return
     */
    public ProducerZooKeeper getProducerZooKeeper() {
        return this.producerZooKeeper;
    }


    /**
     * 返回消费者和zk交互管理器
     * 
     * @return
     */
    public ConsumerZooKeeper getConsumerZooKeeper() {
        return this.consumerZooKeeper;
    }


    /**
     * 返回本地恢复消息管理器
     * 
     * @return
     */
    public RecoverManager getRecoverStorageManager() {
        return this.recoverManager;
    }


    /**
     * 返回此工厂创建的所有子对象，如生产者、消费者等
     * 
     * @return
     */
    public CopyOnWriteArrayList<Shutdownable> getChildren() {
        return this.children;
    }


    public MetaMessageSessionFactory(final MetaClientConfig metaClientConfig) throws MetaClientException {
        super();
        this.checkConfig(metaClientConfig);
        this.metaClientConfig = metaClientConfig;
        final ClientConfig clientConfig = new ClientConfig();
        clientConfig.setTcpNoDelay(false);
        clientConfig.setWireFormatType(new MetamorphosisWireFormatType());
        clientConfig.setMaxScheduleWrittenBytes(Runtime.getRuntime().maxMemory() / 3);
        try {
            this.remotingClient = new RemotingClientWrapper(RemotingFactory.connect(clientConfig));
        }
        catch (final NotifyRemotingException e) {
            throw new NetworkException("Create remoting client failed", e);
        }
        // 如果有设置，则使用设置的url并连接，否则使用zk发现服务器
        if (this.metaClientConfig.getServerUrl() != null) {
            this.connectServer(this.metaClientConfig);
        }
        else {
            this.initZooKeeper();
        }

        this.producerZooKeeper =
                new ProducerZooKeeper(this.metaZookeeper, this.remotingClient, this.zkClient, metaClientConfig);
        this.sessionIdGenerator = new IdGenerator();
        // modify by wuhua
        this.consumerZooKeeper = this.initConsumerZooKeeper(this.remotingClient, this.zkClient, this.zkConfig);
        this.zkClientChangedListeners.add(this.producerZooKeeper);
        this.zkClientChangedListeners.add(this.consumerZooKeeper);
        this.subscribeInfoManager = new SubscribeInfoManager();
        this.recoverManager = new RecoverStorageManager(this.metaClientConfig, this.subscribeInfoManager);
        this.shutdownHook = new Thread() {

            @Override
            public void run() {
                try {
                    MetaMessageSessionFactory.this.isHutdownHookCalled = true;
                    MetaMessageSessionFactory.this.shutdown();
                }
                catch (final MetaClientException e) {
                    log.error("关闭session factory失败", e);
                }
            }

        };
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);
    }


    // add by wuhua
    protected ConsumerZooKeeper initConsumerZooKeeper(final RemotingClientWrapper remotingClientWrapper,
            final ZkClient zkClient2, final ZKConfig config) {
        return new ConsumerZooKeeper(this.metaZookeeper, this.remotingClient, this.zkClient, this.zkConfig);
    }


    private void checkConfig(final MetaClientConfig metaClientConfig) throws MetaClientException {
        if (metaClientConfig == null) {
            throw new MetaClientException("null configuration");
        }
    }


    private void connectServer(final MetaClientConfig metaClientConfig) throws NetworkException {
        try {
            this.remotingClient.connect(metaClientConfig.getServerUrl());
            this.remotingClient.awaitReadyInterrupt(metaClientConfig.getServerUrl());
        }
        catch (final NotifyRemotingException e) {
            throw new NetworkException("Connect to " + metaClientConfig.getServerUrl() + " failed", e);
        }
        catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


    private void initZooKeeper() throws MetaClientException {
        ZKConfig userZK = this.metaClientConfig.getZkConfig();

        if (userZK == null) {
            this.zkConfig = this.loadZkConfigFromDiamond();
        }
        else if (userZK.zkConnect == null) {
            this.zkConfig = this.loadZkConfigFromDiamond();
            this.zkConfig.zkConnectionTimeoutMs = userZK.zkConnectionTimeoutMs;
            this.zkConfig.zkSessionTimeoutMs = userZK.zkSessionTimeoutMs;
            this.zkConfig.zkSyncTimeMs = userZK.zkSyncTimeMs;
        }
        else {
            this.zkConfig = userZK;
        }

        if (this.zkConfig != null) {
            this.zkClient =
                    new ZkClient(this.zkConfig.zkConnect, this.zkConfig.zkSessionTimeoutMs,
                        this.zkConfig.zkConnectionTimeoutMs, new ZkUtils.StringSerializer());
            this.metaZookeeper = new MetaZookeeper(this.zkClient, this.zkConfig.zkRoot);
        }
        else {
            throw new MetaClientException("No zk config offered");
        }
    }


    /*
     * (non-Javadoc)
     * 
     * @see com.taobao.metamorphosis.client.SessionFactory#close()
     */
    @Override
    public void shutdown() throws MetaClientException {
        if (this.shutdown) {
            return;
        }
        this.shutdown = true;
        if (this.diamondManager != null) {
            this.diamondManager.close();
        }
        this.recoverManager.shutdown();
        // this.localMessageStorageManager.shutdown();
        for (final Shutdownable child : this.children) {
            child.shutdown();
        }
        try {
            this.remotingClient.stop();
        }
        catch (final NotifyRemotingException e) {
            throw new NetworkException("Stop remoting client failed", e);
        }
        if (this.zkClient != null) {
            this.zkClient.close();
        }
        if (!this.isHutdownHookCalled) {
            Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
        }

    }


    private ZKConfig loadZkConfigFromDiamond() {
        ManagerListener managerListener = new ManagerListener() {
            private volatile boolean firstCall = true;


            @Override
            public void receiveConfigInfo(final String configInfo) {
                if (firstCall) {
                    firstCall = false;
                    log.info("It's first call, Receiving new diamond zk config:" + configInfo);
                    return;
                }

                log.info("Receiving new diamond zk config:" + configInfo);
                log.info("Closing zk client");

                MetaMessageSessionFactory.this.zkClient.close();
                final Properties properties = new Properties();
                try {
                    properties.load(new StringReader(configInfo));
                    final ZKConfig zkConfig = DiamondUtils.getZkConfig(properties);
                    MetaMessageSessionFactory.this.zkClient.close();
                    Thread.sleep(zkConfig.zkSyncTimeMs);
                    log.info("Initialize zk client...");
                    final ZkClient newClient =
                            new ZkClient(zkConfig.zkConnect, zkConfig.zkSessionTimeoutMs,
                                zkConfig.zkConnectionTimeoutMs, new ZkUtils.StringSerializer());
                    log.info("Begin to notify zkClient has been changed...");
                    MetaMessageSessionFactory.this.metaZookeeper.setZkClient(newClient);
                    MetaMessageSessionFactory.this.notifyZkClientChangedBefore(newClient);
                    MetaMessageSessionFactory.this.notifyZkClientChanged(newClient);
                    MetaMessageSessionFactory.this.zkClient = newClient;
                    log.info("End notifying zkClient has been changed...");
                }
                catch (final Exception e) {
                    log.error("从diamond加载zk配置失败", e);
                }
            }


            @Override
            public Executor getExecutor() {
                return null;
            }
        };
        // 尝试从diamond获取
        this.diamondManager =
                new DefaultDiamondManager(this.metaClientConfig.getDiamondZKGroup(),
                    this.metaClientConfig.getDiamondZKDataId(), (ManagerListener) null);
        ZKConfig zkConfig = DiamondUtils.getZkConfig(this.diamondManager, 10000);
        this.diamondManager.addListeners(Arrays.asList(managerListener));
        return zkConfig;
    }


    private void notifyZkClientChangedBefore(final ZkClient zkClient) {
        for (final ZkClientChangedListener listener : this.zkClientChangedListeners) {
            try {
                listener.onZkClientChangedBefore(zkClient);
            }
            catch (final Throwable t) {
                log.error("更新zKClient失败", t);
            }
        }
    }


    private void notifyZkClientChanged(final ZkClient zkClient) {
        for (final ZkClientChangedListener listener : this.zkClientChangedListeners) {
            try {
                listener.onZkClientChanged(zkClient);
            }
            catch (final Throwable t) {
                log.error("更新zKClient失败", t);
            }
        }
    }


    /*
     * (non-Javadoc)
     * 
     * @see
     * com.taobao.metamorphosis.client.SessionFactory#createProducer(com.taobao
     * .metamorphosis.client.producer.PartitionSelector)
     */
    @Override
    public MessageProducer createProducer(final PartitionSelector partitionSelector) {
        return this.createProducer(partitionSelector, false);
    }


    /*
     * (non-Javadoc)
     * 
     * @see com.taobao.metamorphosis.client.SessionFactory#createProducer()
     */
    @Override
    public MessageProducer createProducer() {
        return this.createProducer(new RoundRobinPartitionSelector(), false);
    }


    /*
     * (non-Javadoc)
     * 
     * @see
     * com.taobao.metamorphosis.client.SessionFactory#createProducer(boolean)
     */
    @Override
    @Deprecated
    public MessageProducer createProducer(final boolean ordered) {
        return this.createProducer(new RoundRobinPartitionSelector(), ordered);
    }


    /*
     * (non-Javadoc)
     * 
     * @see
     * com.taobao.metamorphosis.client.SessionFactory#createProducer(com.taobao
     * .metamorphosis.client.producer.PartitionSelector, boolean)
     */
    @Override
    @Deprecated
    public MessageProducer createProducer(final PartitionSelector partitionSelector, final boolean ordered) {
        if (partitionSelector == null) {
            throw new IllegalArgumentException("Null partitionSelector");
        }
        return this.addChild(new SimpleMessageProducer(this, this.remotingClient, partitionSelector,
            this.producerZooKeeper, this.sessionIdGenerator.generateId()));
    }


    protected <T extends Shutdownable> T addChild(final T child) {
        this.children.add(child);
        return child;
    }


    /**
     * 删除子会话
     * 
     * @param <T>
     * @param child
     */
    public <T extends Shutdownable> void removeChild(final T child) {
        this.children.remove(child);
    }


    private synchronized MessageConsumer createConsumer0(final ConsumerConfig consumerConfig,
            final OffsetStorage offsetStorage, final RecoverManager recoverManager0) {
        if (consumerConfig.getServerUrl() == null) {
            consumerConfig.setServerUrl(this.metaClientConfig.getServerUrl());
        }
        if (offsetStorage == null) {
            throw new InvalidOffsetStorageException("Null offset storage");
        }
        // 必要时启动recover
        if (!recoverManager0.isStarted()) {
            recoverManager0.start(this.metaClientConfig);
        }
        this.checkConsumerConfig(consumerConfig);
        return this.addChild(new SimpleMessageConsumer(this, this.remotingClient, consumerConfig,
            this.consumerZooKeeper, this.producerZooKeeper, this.subscribeInfoManager, recoverManager0,
            offsetStorage, this.createLoadBalanceStrategy(consumerConfig)));
    }


    protected LoadBalanceStrategy createLoadBalanceStrategy(final ConsumerConfig consumerConfig) {
        switch (consumerConfig.getLoadBalanceStrategyType()) {
        case DEFAULT:
            return new DefaultLoadBalanceStrategy();
        case CONSIST:
            return new ConsisHashStrategy();
        default:
            throw new IllegalArgumentException("Unknow load balance strategy type:"
                    + consumerConfig.getLoadBalanceStrategyType());
        }
    }


    protected MessageConsumer createConsumer(final ConsumerConfig consumerConfig,
            final OffsetStorage offsetStorage, final RecoverManager recoverManager0) {
        OffsetStorage offsetStorageCopy = offsetStorage;
        if (offsetStorageCopy == null) {
            offsetStorageCopy = new ZkOffsetStorage(this.metaZookeeper, this.zkClient);
            this.zkClientChangedListeners.add((ZkOffsetStorage) offsetStorageCopy);
        }

        return this.createConsumer0(consumerConfig, offsetStorageCopy, recoverManager0 != null ? recoverManager0
                : this.recoverManager);

    }


    @Override
    public MessageConsumer createConsumer(final ConsumerConfig consumerConfig, final OffsetStorage offsetStorage) {
        return this.createConsumer(consumerConfig, offsetStorage, this.recoverManager);
    }


    /*
     * (non-Javadoc)
     * 
     * @see
     * com.taobao.metamorphosis.client.SessionFactory#createConsumer(com.taobao
     * .metamorphosis.client.consumer.ConsumerConfig)
     */
    @Override
    public MessageConsumer createConsumer(final ConsumerConfig consumerConfig) {
        // final ZkOffsetStorage offsetStorage = new
        // ZkOffsetStorage(this.zkClient);
        // this.zkClientChangedListeners.add(offsetStorage);
        // return this.createConsumer(consumerConfig, offsetStorage);
        return this.createConsumer(consumerConfig, null, null);
    }

    static final char[] INVALID_GROUP_CHAR = { '~', '!', '#', '$', '%', '^', '&', '*', '(', ')', '+', '=', '`',
                                              '\'', '"', ',', ';', '/', '?', '[', ']', '<', '>', '.', ':' };


    protected void checkConsumerConfig(final ConsumerConfig consumerConfig) {
        if (StringUtils.isBlank(consumerConfig.getGroup())) {
            throw new InvalidConsumerConfigException("Blank group");
        }
        final char[] chary = new char[consumerConfig.getGroup().length()];
        consumerConfig.getGroup().getChars(0, chary.length, chary, 0);
        for (final char ch : chary) {
            for (final char invalid : INVALID_GROUP_CHAR) {
                if (ch == invalid) {
                    throw new InvalidConsumerConfigException("Group name has invalid character " + ch);
                }
            }
        }
        if (consumerConfig.getFetchRunnerCount() <= 0) {
            throw new InvalidConsumerConfigException("Invalid fetchRunnerCount:"
                    + consumerConfig.getFetchRunnerCount());
        }
        if (consumerConfig.getFetchTimeoutInMills() <= 0) {
            throw new InvalidConsumerConfigException("Invalid fetchTimeoutInMills:"
                    + consumerConfig.getFetchTimeoutInMills());
        }
    }

}
