package com.taobao.metamorphosis.tools.monitor.core;

import java.util.ArrayList;
import java.util.List;

import com.taobao.metamorphosis.Message;
import com.taobao.metamorphosis.cluster.Partition;

/**
 *
 * @author 无花
 * @since 2011-5-25 上午11:56:10
 */

/** 代表一次接收消息的结果 */
public class ReveiceResult {
    private String topic; //接收的topic
    private Partition partition;//从哪个partition接收
    private long offset;//从offset开始接收
    private String serverUrl;//从哪个服务器接收
    private List<Message> messages;
    private Exception e;

    ReveiceResult(String topic, Partition partition, long offset, String serverUrl) {
        this.topic = topic;
        this.partition = partition;
        this.offset = offset;
        this.serverUrl = serverUrl;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void addMessage(Message message) {
        if (messages == null) {
            messages = new ArrayList<Message>();
        }
        messages.add(message);
    }

    public Exception getException() {
        return e;
    }

    public void setException(Exception e) {
        this.e = e;
    }

    /** 是否接收成功(接收到至少一条消息,并且没发生异常) **/
    public boolean isSuccess() {
        return messages != null && !messages.isEmpty() && e == null;
    }

    public String getTopic() {
        return topic;
    }

    public Partition getPartition() {
        return partition;
    }

    public long getOffset() {
        return offset;
    }

    public String getServerUrl() {
        return serverUrl;
    }

}
