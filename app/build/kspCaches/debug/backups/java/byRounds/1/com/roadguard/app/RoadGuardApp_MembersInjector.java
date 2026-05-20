package com.roadguard.app;

import com.roadguard.app.data.repository.SettingsRepository;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

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
public final class RoadGuardApp_MembersInjector implements MembersInjector<RoadGuardApp> {
  private final Provider<SettingsRepository> settingsRepositoryProvider;

  public RoadGuardApp_MembersInjector(Provider<SettingsRepository> settingsRepositoryProvider) {
    this.settingsRepositoryProvider = settingsRepositoryProvider;
  }

  public static MembersInjector<RoadGuardApp> create(
      Provider<SettingsRepository> settingsRepositoryProvider) {
    return new RoadGuardApp_MembersInjector(settingsRepositoryProvider);
  }

  @Override
  public void injectMembers(RoadGuardApp instance) {
    injectSettingsRepository(instance, settingsRepositoryProvider.get());
  }

  @InjectedFieldSignature("com.roadguard.app.RoadGuardApp.settingsRepository")
  public static void injectSettingsRepository(RoadGuardApp instance,
      SettingsRepository settingsRepository) {
    instance.settingsRepository = settingsRepository;
  }
}
