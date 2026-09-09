package in.manmeet.apexledger.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SaakhSecurityProperties.class)
public class ApiConfiguration {}
