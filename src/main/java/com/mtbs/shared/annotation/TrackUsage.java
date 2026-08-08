package com.mtbs.shared.annotation;

import com.mtbs.shared.enums.billing.UsageMetric;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TrackUsage {
    UsageMetric metric();
}
