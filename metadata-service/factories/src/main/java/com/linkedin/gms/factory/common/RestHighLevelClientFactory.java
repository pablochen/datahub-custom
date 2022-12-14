package com.linkedin.gms.factory.common;

import com.linkedin.gms.factory.auth.AwsRequestSigningApacheInterceptor;
import com.linkedin.gms.factory.spring.YamlPropertySourceFactory;
import javax.annotation.Nonnull;
import javax.net.ssl.SSLContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.auth.AuthScope;
import org.springframework.context.annotation.PropertySource;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;

@Slf4j
@Configuration
@PropertySource(value = "classpath:/application.yml", factory = YamlPropertySourceFactory.class)
@Import({ ElasticsearchSSLContextFactory.class })
public class RestHighLevelClientFactory {

  @Value("${elasticsearch.host}")
  private String host;

  @Value("${elasticsearch.port}")
  private Integer port;

  @Value("${elasticsearch.threadCount}")
  private Integer threadCount;

  @Value("${elasticsearch.connectionRequestTimeout}")
  private Integer connectionRequestTimeout;

  @Value("${elasticsearch.username}")
  private String username;

  @Value("${elasticsearch.password}")
  private String password;

  @Value("#{new Boolean('${elasticsearch.useSSL}')}")
  private boolean useSSL;

  @Value("${elasticsearch.pathPrefix}")
  private String pathPrefix;

  @Value("#{new Boolean('${elasticsearch.opensearchUseAwsIamAuth}')}")
  private boolean opensearchUseAwsIamAuth;

  @Value("${elasticsearch.region}")
  private String region;

  @Autowired
  @Qualifier("elasticSearchSSLContext")
  private SSLContext sslContext;

  @Bean(name = "elasticSearchRestHighLevelClient")
  @Nonnull
  protected RestHighLevelClient createInstance() {
    RestClientBuilder restClientBuilder;
    if (useSSL) {
      restClientBuilder = loadRestHttpsClient(host, port, pathPrefix, threadCount, connectionRequestTimeout, sslContext, username,
          password, opensearchUseAwsIamAuth, region);
    } else {
      restClientBuilder = loadRestHttpClient(host, port, pathPrefix, threadCount, connectionRequestTimeout, username,
                                             password, opensearchUseAwsIamAuth, region);
    }

    return new RestHighLevelClient(restClientBuilder);
  }

  @Nonnull
  private static RestClientBuilder loadRestHttpClient(@Nonnull String host, int port, String pathPrefix, int threadCount,
      int connectionRequestTimeout) {
    RestClientBuilder builder = RestClient.builder(new HttpHost(host, port, "http"))
        .setHttpClientConfigCallback(httpAsyncClientBuilder -> httpAsyncClientBuilder
            .setDefaultIOReactorConfig(IOReactorConfig.custom().setIoThreadCount(threadCount).build()));

    if (!StringUtils.isEmpty(pathPrefix)) {
      builder.setPathPrefix(pathPrefix);
    }

    builder.setRequestConfigCallback(
        requestConfigBuilder -> requestConfigBuilder.setConnectionRequestTimeout(connectionRequestTimeout));

    return builder;
  }

  @Nonnull
  private static RestClientBuilder loadRestHttpClient(@Nonnull String host, int port, String pathPrefix, int threadCount,
      int connectionRequestTimeout, String username, String password, boolean opensearchUseAwsIamAuth, String region) {
    RestClientBuilder builder = loadRestHttpClient(host, port, pathPrefix, threadCount, connectionRequestTimeout);

    builder.setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
      public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpAsyncClientBuilder) {
        httpAsyncClientBuilder.setDefaultIOReactorConfig(IOReactorConfig.custom().setIoThreadCount(threadCount).build());

        if (username != null && password != null) {
          final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
          credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));
          httpAsyncClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
        }
        if (opensearchUseAwsIamAuth) {
          HttpRequestInterceptor interceptor = getAwsRequestSigningInterceptor(region);
          httpAsyncClientBuilder.addInterceptorLast(interceptor);
        }

        return httpAsyncClientBuilder;
      }
    });

    return builder;
  }

  @Nonnull
  private static RestClientBuilder loadRestHttpsClient(@Nonnull String host, int port, String pathPrefix, int threadCount,
      int connectionRequestTimeout, @Nonnull SSLContext sslContext, String username, String password,
                                                       boolean opensearchUseAwsIamAuth, String region) {

    final RestClientBuilder builder = RestClient.builder(new HttpHost(host, port, "https"));

    if (!StringUtils.isEmpty(pathPrefix)) {
      builder.setPathPrefix(pathPrefix);
    }

    builder.setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
      public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpAsyncClientBuilder) {
        httpAsyncClientBuilder.setSSLContext(sslContext).setSSLHostnameVerifier(new NoopHostnameVerifier())
            .setDefaultIOReactorConfig(IOReactorConfig.custom().setIoThreadCount(threadCount).build());

        if (username != null && password != null) {
          final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
          credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));
          httpAsyncClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
        } else if (opensearchUseAwsIamAuth) {
          HttpRequestInterceptor interceptor = getAwsRequestSigningInterceptor(region);
          httpAsyncClientBuilder.addInterceptorLast(interceptor);
        }

        return httpAsyncClientBuilder;
      }
    });

    builder.setRequestConfigCallback(
        requestConfigBuilder -> requestConfigBuilder.setConnectionRequestTimeout(connectionRequestTimeout));

    return builder;
  }

  private static HttpRequestInterceptor getAwsRequestSigningInterceptor(String region) {

    if (region == null) {
      throw new NullPointerException("Region must not be null when opensearchUseAwsIamAuth is enabled");
    }
    Aws4Signer signer = Aws4Signer.create();
    // Uses default AWS credentials
    HttpRequestInterceptor interceptor = new AwsRequestSigningApacheInterceptor("es", signer,
        DefaultCredentialsProvider.create(), region);
    return interceptor;
  }
}
