package cn.datacraft;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/** Existing API fixtures represent same-origin browser requests with a valid CSRF token. */
@TestConfiguration(proxyBeanMethods=false)
public class CsrfTestConfiguration {
    @Bean MockMvcBuilderCustomizer browserCsrf(){return builder->builder.defaultRequest(get("/").with(csrf()));}
}
