package cn.jrymos.broadcast.spi;

import java.util.List;


/**
 * 对外提供服务的广播器
 */
public interface BroadcasterService {

    /**
     * 注册一个topic
     */
    void registerTopic(String topic, List<Server> servers);

    /**
     * 给监听了topic的客户端广播一条message
     */
    void broadcast(Message message);

    /**
     * 无延迟的广播一条message
     */
    void immediatelyBroadcast(Message message);


    /**
     * 获取监听topic的连接信息
     */
    ListenInfo getListenInfo(String topic, String username);

}
