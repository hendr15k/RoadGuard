package com.roadguard.app.data.repository;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata("javax.inject.Singleton")
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
public final class SettingsRepository_Factory implements Factory<SettingsRepository> {
  @Override
  public SettingsRepository get() {
    return newInstance();
  }

  public static SettingsRepository_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static SettingsRepository newInstance() {
    return new SettingsRepository();
  }

  private static final class InstanceHolder {
    private static final SettingsRepository_Factory INSTANCE = new SettingsRepository_Factory();
  }
}
