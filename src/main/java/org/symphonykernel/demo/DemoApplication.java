package org.symphonykernel.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.symphonykernel.ai.VectorSearchHelper;
import org.symphonykernel.config.AzureOpenAIConnectionProperties;
import org.symphonykernel.config.DBConnectionProperties;
import org.symphonykernel.config.SymphonyConfig;
import org.symphonykernel.scheduling.DefaultIndexTrakingProvider;

@SpringBootApplication
@EnableConfigurationProperties({SymphonyConfig.class, DBConnectionProperties.class,
    AzureOpenAIConnectionProperties.class})
@ComponentScan(basePackages = "org.symphonykernel", excludeFilters = {
    @ComponentScan.Filter(type = FilterType.REGEX,
        pattern = "org\\.symphonykernel\\.config\\.(SymphonyKernelAutoConfiguration|AzureOpenAiConfig)"),
    @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
        classes = {VectorSearchHelper.class, DefaultIndexTrakingProvider.class})
})
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}