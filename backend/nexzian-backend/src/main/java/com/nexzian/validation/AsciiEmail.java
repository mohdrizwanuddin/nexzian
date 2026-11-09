package com.nexzian.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * The email must follow Nexzian's supported-address policy: printable ASCII only, no whitespace
 * or control characters, at most 150 characters (see {@link com.nexzian.util.EmailNormalizer}).
 *
 * Use it TOGETHER with @NotBlank and @Email on request DTOs that create accounts; it deliberately
 * adds only the policy and leaves "required" and "well-formed" to those. It is NOT applied to the
 * login request: login accepts anything and an unsupported value simply matches no account.
 */
@Documented
@Constraint(validatedBy = AsciiEmailValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface AsciiEmail {

    String message() default "Use an email address with ASCII characters only.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
