package cn.jrymos.broadcast.spi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class ListenInfo {
    private String topic;
    private String host;
    private int port;
    private String clientId;
    private String password;
}
