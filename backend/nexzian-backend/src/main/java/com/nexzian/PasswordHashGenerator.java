package com.nexzian;

import java.util.Scanner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PasswordHashGenerator {

    public static void main(String[] args) {

        Scanner scanner = new Scanner(System.in);

        System.out.print("Enter your new temporary password: ");
        String password = scanner.nextLine();

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        String hash = encoder.encode(password);

        System.out.println("\nGenerated BCrypt hash:");
        System.out.println(hash);

        scanner.close();
    }
}