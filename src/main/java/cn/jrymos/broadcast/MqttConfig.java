package cn.jrymos.broadcast;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
//git submodule update --init --recursive
@Component
@ConfigurationProperties(prefix = "jrymos.mqtt")
@Getter
@Setter
public class MqttConfig {

    public static final String NAME;

    static {
        try {
            NAME = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    private List<String> hosts;
    private int startPort;
    private int endPort;
    private String publisherUsername;
    private String publisherPassword;
    private String publisherClientId;
    private boolean cleanSession = false;
    private long actionResponseTimeout = 1000; // ms
    private int connectionTimeout = 30; // s
    private String charset = "UTF-8";

    public int[] getPorts() {
        return IntStream.range(startPort, endPort + 1).toArray();
    }

    public List<InetSocketAddress> getInetSocketAddresses() {
        int[] ports = getPorts();
        List<InetSocketAddress> inetSocketAddresses = new ArrayList<>(hosts.size() * ports.length);
        for (String host : hosts) {
            for (int port : ports) {
                InetSocketAddress socketAddress = InetSocketAddress.createUnresolved(host, port);
                inetSocketAddresses.add(socketAddress);
            }
        }
        return inetSocketAddresses;
    }

    public String getClientId() {
        return NAME + ':' + publisherClientId;
    }
}
