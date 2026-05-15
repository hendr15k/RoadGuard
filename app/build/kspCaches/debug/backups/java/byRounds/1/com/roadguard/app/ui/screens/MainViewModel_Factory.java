package com.roadguard.app.ui.screens;

import com.roadguard.app.domain.usecase.GetSettingsUseCase;
import com.roadguard.app.domain.usecase.UpdateSettingsUseCase;
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
public final class MainViewModel_Factory implements Factory<MainViewModel> {
  private final Provider<GetSettingsUseCase> getSettingsUseCaseProvider;

  private final Provider<UpdateSettingsUseCase> updateSettingsUseCaseProvider;

  public MainViewModel_Factory(Provider<GetSettingsUseCase> getSettingsUseCaseProvider,
      Provider<UpdateSettingsUseCase> updateSettingsUseCaseProvider) {
    this.getSettingsUseCaseProvider = getSettingsUseCaseProvider;
    this.updateSettingsUseCaseProvider = updateSettingsUseCaseProvider;
  }

  @Override
  public MainViewModel get() {
    return newInstance(getSettingsUseCaseProvider.get(), updateSettingsUseCaseProvider.get());
  }

  public static MainViewModel_Factory create(
      Provider<GetSettingsUseCase> getSettingsUseCaseProvider,
      Provider<UpdateSettingsUseCase> updateSettingsUseCaseProvider) {
    return new MainViewModel_Factory(getSettingsUseCaseProvider, updateSettingsUseCaseProvider);
  }

  public static MainViewModel newInstance(GetSettingsUseCase getSettingsUseCase,
      UpdateSettingsUseCase updateSettingsUseCase) {
    return new MainViewModel(getSettingsUseCase, updateSettingsUseCase);
  }
}
