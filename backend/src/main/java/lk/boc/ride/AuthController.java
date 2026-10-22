package lk.boc.ride;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @GetMapping("/me")
    public Map<String, Object> me(
            @AuthenticationPrincipal Jwt jwt
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("uid", jwt.getSubject());
        result.put("email", jwt.getClaimAsString("email"));
        result.put(
                "emailVerified",
                Boolean.TRUE.equals(
                        jwt.getClaimAsBoolean("email_verified")
                )
        );
        result.put("status", "TOKEN_VERIFIED");

        return result;
    }
}