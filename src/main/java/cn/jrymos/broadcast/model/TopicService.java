package cn.jrymos.broadcast.model;

import cn.jrymos.broadcast.spi.Server;
import cn.jrymos.spring.custom.injection.redisson.RedissonKey;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RMap;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管理topic
 */
@Service
@RequiredArgsConstructor
public class TopicService {

    @RedissonKey(redisKey = "topicToServer")
    private final RMap<String, List<Server>> topicToServer;

    public List<Server> getServers(String topic) {
        return topicToServer.get(topic);
    }

    public void registerTopic(String topic, List<Server> servers) {
        topicToServer.put(topic, servers);
    }
}
