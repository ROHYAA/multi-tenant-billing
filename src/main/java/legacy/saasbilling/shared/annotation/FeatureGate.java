package legacy.saasbilling.shared.annotation;

import legacy.saasbilling.shared.enums.UsageMetric;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FeatureGate {
    UsageMetric metric();
}
