package io.github.joyen09.exchangecore;

import io.github.joyen09.exchangecore.config.ExchangeProperties;
import io.github.joyen09.exchangecore.idempotency.IdempotencyProperties;
import io.github.joyen09.exchangecore.outbox.OutboxProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ExchangeProperties.class, OutboxProperties.class, IdempotencyProperties.class})
public class ExchangeCoreLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExchangeCoreLabApplication.class, args);
    }
}
