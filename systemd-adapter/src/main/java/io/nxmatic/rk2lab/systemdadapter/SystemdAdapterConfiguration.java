package io.nxmatic.rk2lab.systemdadapter;

import de.thjom.java.systemd.Systemd;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SystemdAdapterProperties.class)
class SystemdAdapterConfiguration {

  @Bean(destroyMethod = "disconnect")
  DBusConnection systemBusConnection() throws DBusException {
    return DBusConnectionBuilder.forSystemBus().build();
  }

  @Bean
  Systemd systemd(DBusConnection systemBusConnection) {
    return Systemd.fromConnection(systemBusConnection);
  }
}
