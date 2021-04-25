package cn.jrymos.broadcast;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.wumii.live.exception.NoServerException;
import com.wumii.model.service.ExecutorUtils;
import com.wumii.model.service.NamedThreadFactory;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttClientPersistence;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * manage mqttClients and publish message
 * a publisher has all mqttClients, one server creates one mqttClient
 *                    -> mqttClient
 *  biz -> publisher  -> mqttClient
 *                    -> mqttClient
 */
@Slf4j
public class Publisher implements Closeable {

    /**
     *  table:
     *
     *  host/port   1881    1882
     *  mqtt-1      c1      c2
     *  mqtt-2      c3      c4
     *
     *  c1、c2、c3、c4 are mqttClient
     */
    final Table<String, Integer, PublishWorker> hostToPortToMqttClient;
    private final MqttConfig mqttConfig;
    private final Function<String, MqttCallback> callbackCreator;
    private final ExecutorService publishExecutorService;
    private static final String publishName = "process-publish-chat-message";
    volatile boolean shutdown;

    /**
     *
     * @param mqttConfig mqtt server config
     * @param callbackCreator mqttCallback: publish success call back, if null use LogMqttCallback
     * @throws MqttException create anyone mqttClient failed
     */
    public Publisher(MqttConfig mqttConfig, @Nullable Function<String, MqttCallback> callbackCreator) throws MqttException {
        this.mqttConfig = mqttConfig;
        this.callbackCreator = callbackCreator;
        int[] ports = mqttConfig.getPorts();
        hostToPortToMqttClient = HashBasedTable.create(mqttConfig.getHosts().size(), ports.length);
        publishExecutorService = new ThreadPoolExecutor(ports.length, ports.length, 10,
            TimeUnit.MINUTES, new LinkedBlockingQueue<>(), new NamedThreadFactory(publishName));
        for (String host : mqttConfig.getHosts()) {
            for (int port : ports) {
                reNewMqttClientConnect(host, port, new LinkedBlockingQueue<>(10000));
            }
        }
    }

    private void reNewMqttClientConnect(String host, int port, LinkedBlockingQueue<ClientMqttMessage> queue) throws MqttException {
        PublishWorker mqttClient = getPublishWorker(host, port, queue);
        hostToPortToMqttClient.put(host, port, mqttClient);
        publishExecutorService.submit(mqttClient);
        log.info("mqtt client:" + mqttClient.getClientId() + " successfully connected to " + mqttClient.getServerURI());
    }

    PublishWorker getPublishWorker(String host, int port, LinkedBlockingQueue<ClientMqttMessage> queue) throws MqttException {
        String clientId = mqttConfig.getClientId();
        String serverUri = "tcp://" + host + ":" + port;
        MqttConnectOptions connectOptions = new MqttConnectOptions();
        connectOptions.setUserName(mqttConfig.getPublisherUsername());
        connectOptions.setPassword(mqttConfig.getPublisherPassword().toCharArray());
        connectOptions.setCleanSession(mqttConfig.isCleanSession());
        connectOptions.setConnectionTimeout(mqttConfig.getConnectionTimeout());
        PublishWorker mqttClient = new PublishWorker(serverUri, clientId, new MemoryPersistence());
        mqttClient.setHost(host);
        mqttClient.setPort(port);
        mqttClient.setDeath(false);
        mqttClient.setQueue(queue);
        // exception will be thrown if timeout expires
        mqttClient.setTimeToWait(mqttConfig.getActionResponseTimeout());
        mqttClient.connect(connectOptions);
        if (callbackCreator != null) {
            mqttClient.setCallback(callbackCreator.apply(serverUri));
        } else {
            // default use LogMqttCallback
            mqttClient.setCallback(new LogMqttCallback(serverUri));
        }
        return mqttClient;
    }

    public void publishToSpecificServer(List<InetSocketAddress> servers, String topic, String msg) {
        for (InetSocketAddress server : servers) {
            publishToOneServer(server.getHostName(), server.getPort(), topic, msg);
        }
    }

    void publishToOneServer(String host, int port, String topic, String msg) {
        PublishWorker mqttClient = hostToPortToMqttClient.get(host, port);
        if (mqttClient == null) {
            throw new NoServerException(host, port);
        }
        publish(mqttClient, topic, msg);
    }


    private void publish(PublishWorker mqttClient, String topic, String msg) {
        try {

            mqttClient.publish(topic, new MqttMessage(msg.getBytes(mqttConfig.getCharset())));
        } catch (UnsupportedEncodingException e) {
            log.error("handleMessageSignal mqtt failed charset not support:{}", mqttConfig.getCharset(), e);
        }
    }

    @Override
    public void close() {
        shutdown = true;
        ExecutorUtils.shutdownGracefully(publishExecutorService, publishName);
        for (MqttClient mqttClient : hostToPortToMqttClient.values()) {
            try {
                if (mqttClient.isConnected()) {
                    mqttClient.disconnect();
                }
                mqttClient.close();
            } catch (MqttException e) {
                log.error("close mqtt failed :{}", mqttClient.getServerURI(), e);
            }
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


    class PublishWorker extends MqttClient implements Runnable {

        @Setter LinkedBlockingQueue<ClientMqttMessage> queue;
        @Setter private int port;
        @Setter private String host;
        @Setter volatile boolean death;


        PublishWorker(String serverURI, String clientId, MqttClientPersistence persistence) throws MqttException {
            super(serverURI, clientId, persistence);
        }

        @Override
        public void publish(String topic, MqttMessage message) {
            queue.add(ClientMqttMessage.of(topic, message));
        }

        @Override
        public void run() {
            while (!shutdown && !death) {
                ClientMqttMessage task = null;
                try {
                    task = queue.take();
                    if (!isConnected()) {
                        log.info("publish#reconnect mqtt : {}, task:{}", getServerURI(), task);
                        connect();
                    }
                    super.publish(task.getTopic(), task);
                } catch (InterruptedException e) {
                    log.warn("InterruptedException,{}", getServerURI());
                } catch (MqttException e) {
                    log.error("handleMessageSignal mqtt failed :{}", getServerURI(), e);
                    try {
                        reNewMqttClientConnect(host, port, queue);
                        death = true;
                    } catch (MqttException e1) {
                        log.error("send failed, host:{}, port：{}, info:{}", host, port, task, e1);
                    }
                }
            }
        }
    }
}