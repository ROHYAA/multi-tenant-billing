package com.mtbs.notification.template;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class ThymeleafTemplateRenderer implements TemplateRenderer {

    private final TemplateEngine templateEngine;

    @Override
    public String render(String templateName, Map<String, Object> variables) {
        Context context = new Context(java.util.Locale.ENGLISH);
        if (variables != null) {
            context.setVariables(variables);
        }
        String fullTemplateName = "emails/" + templateName;
        return templateEngine.process(fullTemplateName, context);
    }
}