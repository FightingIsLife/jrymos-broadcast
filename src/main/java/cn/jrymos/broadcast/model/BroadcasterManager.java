package cn.jrymos.broadcast.model;

import cn.jrymos.broadcast.spi.Server;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.xerial.snappy.Snappy;


import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * manage mqttClients and publish message
 * a broadcaster has all mqttClients, one server creates one mqttClient
 * -> mqttClient
 * biz -> publisher  -> mqttClient
 * -> mqttClient
 */
@Slf4j
public class BroadcasterManager implements Closeable {

    /**
     * table:
     * <p>
     * host/port   1881    1882
     * mqtt-1      c1      c2
     * mqtt-2      c3      c4
     * <p>
     * c1、c2、c3、c4 are mqttClient
     */
    final Table<String, Integer, MqttBroadcaster> broadcasters;
    private final BroadcasterConfig broadcasterConfig;
    private final Function<String, MqttCallback> callbackFactory;
    private final ThreadFactory threadFactory;
    volatile boolean shutdown;

    public BroadcasterManager(BroadcasterConfig broadcasterConfig) throws MqttException {
        this(broadcasterConfig, LogMqttCallback::new);
    }


    public BroadcasterManager(BroadcasterConfig broadcasterConfig, Function<String, MqttCallback> callbackFactory) throws MqttException {
        this(broadcasterConfig, callbackFactory, r -> new Thread(r, "broadcaster"));
    }

    /**
     * @param broadcasterConfig      mqtt server config
     * @param callbackFactory mqttCallback: publish success call back, if null use LogMqttCallback
     */
    public BroadcasterManager(BroadcasterConfig broadcasterConfig, Function<String, MqttCallback> callbackFactory, ThreadFactory threadFactory) throws MqttException {
        this.broadcasterConfig = broadcasterConfig;
        this.callbackFactory = callbackFactory;
        this.threadFactory = threadFactory;

        broadcasters = HashBasedTable.create();
        for (Server server : broadcasterConfig.getServers()) {
            MqttBroadcaster mqttBroadcaster = new MqttBroadcaster(server.getHost(), server.getPort(), broadcasterConfig.getThreadCores());
            mqttBroadcaster.connectNewMqttClient();
            broadcasters.put(server.getHost(), server.getPort(), mqttBroadcaster);
        }

    }


    public void broadcast(List<Server> servers, String topic, String msg) {
        for (Server server : servers) {
            broadcast(server.getHost(), server.getPort(), topic, msg);
        }
    }

    void broadcast(String host, int port, String topic, String msg) {
        MqttBroadcaster mqttClient = broadcasters.get(host, port);
        if (mqttClient == null) {
            throw new IllegalArgumentException("not found server " + host + ":" + port);
        }
        broadcast(mqttClient, topic, msg);
    }


    private void broadcast(MqttBroadcaster mqttClient, String topic, String msg) {
        try {
            // 使用Snappy压缩字符串
            if (broadcasterConfig.isCompress()) {
                byte[] snappyBytes = Snappy.compress(msg);
                byte[] utf8Bytes = msg.getBytes(StandardCharsets.UTF_8);
                log.info("string compress length:{}, uft-8 length:{}", snappyBytes, utf8Bytes);
                mqttClient.publish(topic, new MqttMessage(snappyBytes));
            } else {
                mqttClient.publish(topic, new MqttMessage(msg.getBytes(StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            log.error("handleMessageSignal mqtt failed charset not support:{}", msg, e);
        }
    }

    @Override
    public void close() {
        shutdown = true;
        for (MqttBroadcaster mqttBroadcaster : broadcasters.values()) {
            mqttBroadcaster.preClose();
        }
        for (MqttBroadcaster mqttBroadcaster : broadcasters.values()) {
            mqttBroadcaster.safeClose(30, TimeUnit.SECONDS);
        }
        for (MqttBroadcaster mqttBroadcaster : broadcasters.values()) {
            mqttBroadcaster.close();
        }
    }

    public static class LogMqttCallback implements MqttCallback {
        private String serverUri;

        public LogMqttCallback(String serverUri) {
            this.serverUri = serverUri;
        }

        @Override
        public void connectionLost(Throwable cause) {
            log.error("connectionLost,serverUri:{}", serverUri, cause);
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            log.info("messageArrived#serverUri:{},topic:{},message:{}", serverUri, topic, message);
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            log.info("deliveryComplete#serverUri:{}, complete:{}", serverUri, token.getMessageId());
        }
    }


    class MqttBroadcaster implements Closeable, Consumer<MqttMessageWrapper> {

        private final String host;
        private final int port;
        private final ExecutorService executorService;
        private volatile boolean closed = false;
        private MqttClient mqttClient;

        public MqttBroadcaster(String host, int port, int threadCores) {
            this.port = port;
            this.host = host;
            executorService = Executors.newFixedThreadPool(threadCores, threadFactory);
        }

        private synchronized void connectNewMqttClient() throws MqttException {
            if (closed) {
                throw new IllegalStateException("worker already closed " + host + ":" + port);
            }
            tryCloseMqtt();
            String clientId = broadcasterConfig.getBroadcasterClientId();
            String serverUri = "tcp://" + host + ":" + port;
            MqttConnectOptions connectOptions = new MqttConnectOptions();
            connectOptions.setUserName(broadcasterConfig.getBroadcastUsername());
            connectOptions.setPassword(broadcasterConfig.getBroadcastPassword().toCharArray());
            connectOptions.setCleanSession(broadcasterConfig.isCleanSession());
            connectOptions.setConnectionTimeout(broadcasterConfig.getConnectionTimeout());
            MqttClient mqttClient = new MqttClient(serverUri, clientId, new MemoryPersistence());
            // exception will be thrown if timeout expires
            mqttClient.setTimeToWait(broadcasterConfig.getActionResponseTimeout());
            mqttClient.connect(connectOptions);
            if (callbackFactory != null) {
                mqttClient.setCallback(callbackFactory.apply(serverUri));
            } else {
                // default use LogMqttCallback
                mqttClient.setCallback(new LogMqttCallback(serverUri));
            }
            this.mqttClient = mqttClient;
        }

        public void accept(MqttMessageWrapper wrapper) {
            try {
                if (mqttClient == null) {
                    connectNewMqttClient();
                }
                mqttClient.publish(wrapper.getTopic(), wrapper.getMqttMessage());
            } catch (MqttException e) {
                log.error("handleMessageSignal mqtt failed :{}", mqttClient.getServerURI(), e);
                try {
                    connectNewMqttClient();
                } catch (MqttException e1) {
                    log.error("send failed, host:{}, port：{}, info:{}", host, port, wrapper, e1);
                }
            }
        }

        /**
         * @see this#accept(MqttMessageWrapper)
         */
        public void publish(String topic, MqttMessage message) {
            executorService.submit(new MqttMessageWrapper(topic, message, this));
        }

        @Override
        public void close() {
            executorService.shutdownNow();
            closed = true;
            tryCloseMqtt();
        }

        private void tryCloseMqtt() {
            try {
                if (mqttClient == null) {
                    return;
                }
                if (mqttClient.isConnected()) {
                    mqttClient.disconnect();
                }
                mqttClient.close();
            } catch (MqttException e) {
                log.error("close mqtt failed :{}", mqttClient.getServerURI(), e);
            }
        }

        public void preClose() {
            executorService.shutdown();
        }

        public void safeClose(long timeout, TimeUnit unit) {
            executorService.shutdown();
            try {
                executorService.awaitTermination(timeout, unit);
            } catch (InterruptedException e) {
                log.warn("safe close interruptedException " + host + ":" + port);
            }
            if (executorService.isTerminated()) {
                close();
            }
        }

    }


    @ToString
    @Getter
    @RequiredArgsConstructor
    static class MqttMessageWrapper implements Runnable {
        private final String topic;
        private final MqttMessage mqttMessage;
        private final Consumer<MqttMessageWrapper> runnable;


        @Override
        public void run() {
            runnable.accept(this);
        }
    }
}