package com.example.satelite.repositories;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.satelite.models.ControleCursor;

public interface ControleCursorRepository extends JpaRepository<ControleCursor, Integer> {

    Optional<ControleCursor> findBySistemaDestino(String sistemaDestino);
}
