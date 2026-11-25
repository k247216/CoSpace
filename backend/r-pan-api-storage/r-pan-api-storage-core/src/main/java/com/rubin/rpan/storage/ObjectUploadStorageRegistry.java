package com.rubin.rpan.storage;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ObjectUploadStorageRegistry {
  private final Map<String, ObjectUploadStorage> backends;

  public ObjectUploadStorageRegistry(List<ObjectUploadStorage> backends) {
    this.backends =
        backends.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    ObjectUploadStorage::storageType, Function.identity()));
  }

  public ObjectUploadStorage find(String type) {
    return type == null ? null : backends.get(type);
  }

  public Collection<String> types() {
    return backends.keySet();
  }
}
