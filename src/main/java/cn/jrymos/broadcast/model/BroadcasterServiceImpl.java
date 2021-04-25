package cn.jrymos.broadcast.model;

import cn.jrymos.broadcast.spi.BroadcasterService;
import cn.jrymos.broadcast.spi.ListenInfo;
import cn.jrymos.broadcast.spi.Message;
import cn.jrymos.broadcast.spi.Server;
import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BroadcasterServiceImpl implements BroadcasterService, InitializingBean, DisposableBean {

    private final TopicService topicService;
    private final BroadcasterConfig broadcasterConfig;
    private final BroadcastAuthService broadcastAuthService;

    private BroadcasterManager broadcasterManager;
    // 使用消息队列的目的是进行合并写，提升性能
    private LinkedBlockingQueue<Message> messageBufferQueue;
    private volatile boolean shutdown;

    @Override
    public void registerTopic(String topic, List<Server> servers) {
        topicService.registerTopic(topic, servers);
    }

    @Override
    public void broadcast(Message message) {
        checkNotShutDown();
        messageBufferQueue.add(message);
    }

    private void checkNotShutDown() {
        if (shutdown) {
            throw new IllegalStateException("broadcast is shutdown");
        }
    }

    @Override
    public void immediatelyBroadcast(Message message) {
        checkNotShutDown();
        broadcasterManager.broadcast(topicService.getServers(message.getTopic()), message.getTopic(),
            JSON.toJSONString(Collections.singletonList(message.getMessage())));
    }

    @Override
    public ListenInfo getListenInfo(String topic, String username) {
        checkNotShutDown();
        return broadcastAuthService.getListenInfo(topic, username);
    }

    // 200ms 处理一次
    @Scheduled(fixedRate = 200L)
    public void doBroadcastMessages() {
        List<Message> messages = new ArrayList<>();
        messageBufferQueue.drainTo(messages);
        if (messages.isEmpty()) {
            return;
        }
        Map<String, List<Message>> topicToMessages = messages.stream()
            .collect(Collectors.groupingBy(Message::getTopic));
        topicToMessages.forEach((topic, ms) -> {
            String msg = JSON.toJSONString(ms.stream().map(Message::getMessage).collect(Collectors.toList()));
            broadcasterManager.broadcast(topicService.getServers(topic), topic, msg);
        });
    }

    @Override
    public void destroy() {
        shutdown = true;
        while (!messageBufferQueue.isEmpty()) {
            doBroadcastMessages();
        }
        broadcasterManager.close();
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        this.broadcasterManager = new BroadcasterManager(broadcasterConfig);
        this.messageBufferQueue = new LinkedBlockingQueue<>();
    }
}
