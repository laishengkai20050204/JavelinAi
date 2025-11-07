package com.example.config;

import com.example.markdown.MarkdownCanonicalizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MarkdownConfig {
    @Bean
    public MarkdownCanonicalizer markdownCanonicalizer() {
        return new MarkdownCanonicalizer();
    }
}
