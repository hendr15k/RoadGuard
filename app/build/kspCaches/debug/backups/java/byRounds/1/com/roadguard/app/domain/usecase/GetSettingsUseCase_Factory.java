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
public final class GetSettingsUseCase_Factory implements Factory<GetSettingsUseCase> {
  private final Provider<SettingsRepository> repositoryProvider;

  public GetSettingsUseCase_Factory(Provider<SettingsRepository> repositoryProvider) {
    this.repositoryProvider = repositoryProvider;
  }

  @Override
  public GetSettingsUseCase get() {
    return newInstance(repositoryProvider.get());
  }

  public static GetSettingsUseCase_Factory create(Provider<SettingsRepository> repositoryProvider) {
    return new GetSettingsUseCase_Factory(repositoryProvider);
  }

  public static GetSettingsUseCase newInstance(SettingsRepository repository) {
    return new GetSettingsUseCase(repository);
  }
}
