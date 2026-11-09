package com.nexzian.validation;

import com.nexzian.util.EmailNormalizer;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Implements {@link AsciiEmail} by delegating to the single policy definition in
 * {@link EmailNormalizer#findProblem}, so request validation, the service-level safety net and the
 * documented database rule cannot drift apart.
 *
 * It judges the value exactly as the client sent it (untrimmed): a leading or trailing tab or
 * newline is a policy violation here, not something silently cleaned up. Null and empty values
 * are valid for this constraint; @NotBlank reports those.
 */
public class AsciiEmailValidator implements ConstraintValidator<AsciiEmail, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        EmailNormalizer.Problem problem = EmailNormalizer.findProblem(value);
        if (problem == null) {
            return true;
        }

        // Report the specific reason (non-ASCII, whitespace/control, too long) instead of the
        // annotation's default text, which only describes the non-ASCII case.
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(problem.message()).addConstraintViolation();
        return false;
    }
}
