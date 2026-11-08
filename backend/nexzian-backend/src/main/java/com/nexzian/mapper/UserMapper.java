
package com.nexzian.mapper;

import java.util.UUID;

import com.nexzian.dto.UserRequest;
import com.nexzian.dto.UserResponse;
import com.nexzian.entity.User;

public class UserMapper {

    public static User toEntity(UserRequest request) {

        User user = new User();

        user.setTenantId(UUID.fromString(request.getTenantId()));
        user.setFullName(request.getFullName());
        user.setEmail(request.getEmail());
        user.setPassword(request.getPassword());
        user.setRole(request.getRole());

        return user;
    }

    public static UserResponse toResponse(User user) {

        UserResponse response = new UserResponse();

        response.setId(user.getId());
        response.setTenantId(user.getTenantId());
        response.setFullName(user.getFullName());
        response.setEmail(user.getEmail());
        response.setRole(user.getRole());
        response.setStatus(user.getStatus());
        response.setCreatedAt(user.getCreatedAt());
        response.setUpdatedAt(user.getUpdatedAt());

        return response;
    }
}
