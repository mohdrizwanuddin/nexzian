package com.nexzian.dto;

import com.nexzian.validation.AsciiEmail;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class TeamMemberRequest {
    @NotBlank @Size(max = 150) private String fullName;
    @NotBlank @Email @AsciiEmail @Size(max = 150) private String email;
    @NotBlank @Size(min = 8, max = 100) private String password;
    @NotBlank private String role;
    public String getFullName() { return fullName; } public void setFullName(String fullName) { this.fullName = fullName; }
    public String getEmail() { return email; } public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; } public void setPassword(String password) { this.password = password; }
    public String getRole() { return role; } public void setRole(String role) { this.role = role; }
}
