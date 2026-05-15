package com.roadguard.app.domain.usecase;

import com.roadguard.app.data.repository.SettingsRepository;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata
@QualifierMetadata
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava"
})
public final class UpdateSettingsUseCase_Factory implements Factory<UpdateSettingsUseCase> {
  private final Provider<SettingsRepository> repositoryProvider;

  public UpdateSettingsUseCase_Factory(Provider<SettingsRepository> repositoryProvider) {
    this.repositoryProvider = repositoryProvider;
  }

  @Override
  public UpdateSettingsUseCase get() {
    return newInstance(repositoryProvider.get());
  }

  public static UpdateSettingsUseCase_Factory create(
      Provider<SettingsRepository> repositoryProvider) {
    return new UpdateSettingsUseCase_Factory(repositoryProvider);
  }

  public static UpdateSettingsUseCase newInstance(SettingsRepository repository) {
    return new UpdateSettingsUseCase(repository);
  }
}
