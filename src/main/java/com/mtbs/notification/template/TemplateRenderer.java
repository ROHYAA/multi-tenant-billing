package com.mtbs.notification.template;

import java.util.Map;

public interface TemplateRenderer {
    String render(String templateName, Map<String, Object> variables);
}