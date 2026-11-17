package p1228;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

// ===== 1. ProxySupport (環境変数プロキシ対応) =====
// aegis-java と同じ方式。JavaのHttpClientは環境変数 HTTPS_PROXY を
// 自動で読まないため、ここで橋渡しする。認証情報は環境変数から
// 実行時に読むだけで、コードやログには一切出さない。
public final class ProxySupport {
    static {
        // HTTPSトンネル (CONNECT) でも Basic認証を使えるようにする。
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        System.setProperty("jdk.http.auth.proxying.disabledSchemes", "");
    }

    private ProxySupport() {}

    // ----- 1.1 プロキシ解決 -----
    private static String envProxy() {
        for (String key : new String[]{"HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy"}) {
            String v = System.getenv(key);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static boolean isLocal(String host) {
        return host == null || host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
    }

    public static HttpClient newClient(Duration connectTimeout) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(connectTimeout);
        String proxyUrl = envProxy();
        if (proxyUrl != null) {
            try {
                URI uri = URI.create(proxyUrl);
                String host = uri.getHost();
                int port = uri.getPort() >= 0 ? uri.getPort() : 3128;
                if (host != null) {
                    builder.proxy(new ProxySelector() {
                        @Override public List<Proxy> select(URI target) {
                            if (isLocal(target.getHost())) return List.of(Proxy.NO_PROXY);
                            return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
                        }
                        @Override public void connectFailed(URI target, SocketAddress sa, IOException ioe) {}
                    });
                    String userInfo = uri.getUserInfo();
                    if (userInfo != null && userInfo.contains(":")) {
                        String user = userInfo.substring(0, userInfo.indexOf(':'));
                        String pass = userInfo.substring(userInfo.indexOf(':') + 1);
                        builder.authenticator(new Authenticator() {
                            @Override protected PasswordAuthentication getPasswordAuthentication() {
                                if (getRequestorType() == RequestorType.PROXY) {
                                    return new PasswordAuthentication(user, pass.toCharArray());
                                }
                                return null;
                            }
                        });
                    }
                }
            } catch (Exception ignored) {
                // プロキシ解析に失敗したら直接接続にフォールバックする（認証情報は出さない）
            }
        }
        return builder.build();
    }
}
