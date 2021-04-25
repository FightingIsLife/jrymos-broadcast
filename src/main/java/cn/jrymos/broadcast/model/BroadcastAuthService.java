package cn.jrymos.broadcast.model;

import cn.jrymos.broadcast.spi.ListenInfo;
import cn.jrymos.broadcast.spi.Server;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class BroadcastAuthService {

    private static final String CONNECTION_AUTH_KEY = "mqtt:chat:user";

    private final TopicService topicService;
    private final RedissonClient redissonClient;
    private final BroadcasterConfig broadcasterConfig;

    public ListenInfo getListenInfo(String topic, String username) {
        List<Server> servers = topicService.getServers(topic);
        Server server = servers.get(RandomUtils.nextInt(0, servers.size()));
        // 生成动态密码
        String password = RandomStringUtils.randomAlphabetic(10);
        String clientId = username + "-" + topic;
        RBucket<String> passwordBucket = redissonClient.getBucket(CONNECTION_AUTH_KEY + ":" + username + ":" + clientId);
        passwordBucket.set(password);
        passwordBucket.expire(5, TimeUnit.MINUTES);
        return ListenInfo.builder()
            .host(server.getHost())
            .port(server.getPort())
            .password(password)
            .topic(topic)
            .clientId(clientId)
            .build();
    }

    @PostConstruct
    public void initBroadcastAuth() {
        String key = CONNECTION_AUTH_KEY + ":" + broadcasterConfig.getBroadcastUsername() + ":" + broadcasterConfig.getBroadcasterClientId();
        RBucket<String> connectionAuth = redissonClient.getBucket(key, StringCodec.INSTANCE);
        connectionAuth.set(broadcasterConfig.getBroadcastPassword());
    }

}
