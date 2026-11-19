package com.example.satelite.repositories;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.satelite.models.EslRequestTelemetryModel;

public interface EslRequestTelemetryRepository extends JpaRepository<EslRequestTelemetryModel, Long> {
}
