package inspector.store;

import inspector.store.entity.MetaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** The {@code meta} table: {@code schema_version} and {@code corpus}, nothing else. */
public interface MetaRepository extends JpaRepository<MetaEntity, String> {
}
