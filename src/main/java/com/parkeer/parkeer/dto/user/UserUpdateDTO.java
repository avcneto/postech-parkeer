package com.parkeer.parkeer.dto.user;

public record UserUpdateDTO(
        String name,
        String address,
        String addressNumber,
        String zipCode,
        String phone,
        String cpf,
        String email,
        String password
) {
}
