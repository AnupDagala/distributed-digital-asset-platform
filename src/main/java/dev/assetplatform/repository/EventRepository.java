package dev.assetplatform.repository;

import dev.assetplatform.domain.ProcessingEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<ProcessingEvent, Long> {
  List<ProcessingEvent> findByAssetIdAndIdGreaterThanOrderById(
      UUID assetId, long cursor, Pageable page);
}
