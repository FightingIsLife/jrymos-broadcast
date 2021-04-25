package cn.jrymos.broadcast.spi;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Server {
    private String host;
    private int port;
}
