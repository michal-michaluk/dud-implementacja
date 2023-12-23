package devices.configuration.device;

import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.AbstractAggregateRoot;

import javax.persistence.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static devices.configuration.device.DomainEvent.*;
import static devices.configuration.device.OpeningHours.OpeningTime.*;

@Entity
@Table(name = "device")
class Device extends AbstractAggregateRoot<Device> {

    @Getter
    @Id
    @Column(name = "device_id")
    private String deviceId;
    @Version
    private Long version;

    private String operator;
    private String provider;

    private String street;
    @Column(name = "house_number")
    private String houseNumber;
    private String city;
    @Column(name = "postal_code")
    private String postalCode;
    private String state;
    private String country;
    @Column(precision = 18, scale = 15)
    private BigDecimal longitude;
    @Column(precision = 18, scale = 15)
    private BigDecimal latitude;

    private boolean autoStart;
    @Column(name = "remote_control")
    private boolean remoteControl;
    private boolean billing;
    private boolean reimbursement;
    @Column(name = "show_on_map")
    private boolean showOnMap;
    @Column(name = "public_access")
    private boolean publicAccess;

    @OneToMany(orphanRemoval = true)
    @JoinColumn(name = "device_id", referencedColumnName = "device_id")
    private List<OpeningHoursEntity> openingHours = new ArrayList<>();

    static Device newDevice(String deviceId) {
        Device device = new Device();
        device.deviceId = deviceId;
        return device;
    }

    void resetToDefaults() {
        updateLocation(null);
        updateOpeningHours(OpeningHours.alwaysOpened());
        updateSettings(Settings.defaultSettings());
    }

    void assignTo(Ownership ownership) {
        Objects.requireNonNull(ownership);
        if (!Objects.equals(getOwnership(), ownership)) {
            this.operator = ownership.operator();
            this.provider = ownership.provider();
            registerEvent(new OwnershipUpdated(deviceId, getOwnership()));

            if (ownership.isUnowned()) {
                resetToDefaults();
            }
        }
    }

    void updateLocation(Location location) {
        if (Objects.equals(getLocation(), location)) {
            return;
        }

        if (location != null) {
            this.street = location.street();
            this.houseNumber = location.houseNumber();
            this.city = location.city();
            this.postalCode = location.postalCode();
            this.state = location.state();
            this.country = location.country();
            this.longitude = location.coordinates().longitude();
            this.latitude = location.coordinates().latitude();

            registerEvent(new LocationUpdated(deviceId, getLocation()));
        } else {
            this.street = null;
            this.houseNumber = null;
            this.city = null;
            this.postalCode = null;
            this.state = null;
            this.country = null;
            this.longitude = null;
            this.latitude = null;

            registerEvent(new LocationUpdated(deviceId, getLocation()));
        }
    }

    void updateOpeningHours(OpeningHours openingHours) {
        Objects.requireNonNull(openingHours);
        if (!Objects.equals(getOpeningHours(), openingHours)) {
            this.openingHours.clear();
            this.openingHours.addAll(OpeningHoursEntity.of(deviceId, openingHours));
            registerEvent(new OpeningHoursUpdated(deviceId, getOpeningHours()));
        }
    }

    void updateSettings(Settings settings) {
        Objects.requireNonNull(settings);
        boolean changed = false;
        if (settings.autoStart() != null && !Objects.equals(this.autoStart, settings.autoStart())) {
            this.autoStart = settings.autoStart();
            changed = true;
        }
        if (settings.remoteControl() != null && !Objects.equals(this.remoteControl, settings.remoteControl())) {
            this.remoteControl = settings.remoteControl();
            changed = true;
        }
        if (settings.billing() != null && !Objects.equals(this.billing, settings.billing())) {
            this.billing = settings.billing();
            changed = true;
        }
        if (settings.reimbursement() != null && !Objects.equals(this.reimbursement, settings.reimbursement())) {
            this.reimbursement = settings.reimbursement();
            changed = true;
        }
        if (settings.showOnMap() != null && !Objects.equals(this.showOnMap, settings.showOnMap())) {
            this.showOnMap = settings.showOnMap();
            changed = true;
        }
        if (settings.publicAccess() != null && !Objects.equals(this.publicAccess, settings.publicAccess())) {
            this.publicAccess = settings.publicAccess();
            changed = true;
        }

        if (changed) {
            registerEvent(new SettingsUpdated(deviceId, getSettings()));
        }
    }

    private Violations checkViolations() {
        return new Violations(
                operator == null,
                provider == null,
                getLocation() == null,
                showOnMap && getLocation() == null,
                showOnMap && !publicAccess
        );
    }

    DeviceConfiguration toDeviceConfiguration() {
        Violations violations = checkViolations();
        Visibility visibility = Visibility.basedOn(
                violations.isValid() && this.publicAccess,
                this.showOnMap
        );
        return new DeviceConfiguration(
                deviceId,
                getOwnership(),
                getLocation(),
                getOpeningHours(),
                getSettings(),
                violations,
                visibility
        );
    }

    private Ownership getOwnership() {
        return new Ownership(operator, provider);
    }

    private Location getLocation() {
        if (street == null && houseNumber == null && city == null && postalCode == null && state == null && country == null && longitude == null && latitude == null) {
            return null;
        }
        return new Location(
                street,
                houseNumber,
                city,
                postalCode,
                state,
                country,
                new Location.Coordinates(longitude, latitude)
        );
    }

    private OpeningHours getOpeningHours() {
        if (openingHours.isEmpty()) {
            return OpeningHours.alwaysOpened();
        } else {
            var week = openingHours.stream()
                    .collect(Collectors.toUnmodifiableMap(
                            OpeningHoursEntity::getDayOfWeek,
                            OpeningHoursEntity::toOpeningTime
                    ));
            return OpeningHours.openAt(
                    week.getOrDefault("monday", closed24h()),
                    week.getOrDefault("tuesday", closed24h()),
                    week.getOrDefault("wednesday", closed24h()),
                    week.getOrDefault("thursday", closed24h()),
                    week.getOrDefault("friday", closed24h()),
                    week.getOrDefault("saturday", closed24h()),
                    week.getOrDefault("sunday", closed24h())
            );
        }
    }

    private Settings getSettings() {
        return Settings.builder()
                .autoStart(autoStart)
                .remoteControl(remoteControl)
                .billing(billing)
                .reimbursement(reimbursement)
                .showOnMap(showOnMap)
                .publicAccess(publicAccess)
                .build();
    }

    void clearEvents() {
        super.clearDomainEvents();
    }

    @Data
    @Entity
    @Table(name = "opening_hours")
    @NoArgsConstructor
    static class OpeningHoursEntity {
        @Id
        private Long id;
        @Column(name = "device_id")
        private String deviceId;
        private String dayOfWeek;
        private boolean open24h;
        private boolean closed;
        private Integer open;
        private Integer close;

        static List<OpeningHoursEntity> of(String deviceId, OpeningHours openingHours) {
            if (openingHours.alwaysOpen()) {
                return List.of();
            }
            return List.of(
                    OpeningHoursEntity.of(deviceId, "monday", openingHours.opened().monday()),
                    OpeningHoursEntity.of(deviceId, "tuesday", openingHours.opened().tuesday()),
                    OpeningHoursEntity.of(deviceId, "wednesday", openingHours.opened().wednesday()),
                    OpeningHoursEntity.of(deviceId, "thursday", openingHours.opened().thursday()),
                    OpeningHoursEntity.of(deviceId, "friday", openingHours.opened().friday()),
                    OpeningHoursEntity.of(deviceId, "saturday", openingHours.opened().saturday()),
                    OpeningHoursEntity.of(deviceId, "sunday", openingHours.opened().sunday())
            );
        }

        static OpeningHoursEntity of(String deviceId, String dayOfWeek, OpeningHours.OpeningTime openingTime) {
            OpeningHoursEntity entity = new OpeningHoursEntity();
            entity.deviceId = deviceId;
            entity.dayOfWeek = dayOfWeek;
            switch (openingTime) {
                case OpeningHours.OpeningTime.Opened24h ignored -> {
                    entity.open24h = true;
                    entity.closed = false;
                }
                case OpeningHours.OpeningTime.Closed24h ignored -> {
                    entity.open24h = false;
                    entity.closed = true;
                }
                case OpeningHours.OpeningTime.OpenTime time -> {
                    entity.open = time.time().get(0).open().getHour();
                    entity.close = time.time().get(0).close().getHour();
                }
            }
            return entity;
        }

        OpeningHours.OpeningTime toOpeningTime() {
            if (open24h) {
                return opened24h();
            }
            if (closed) {
                return closed24h();
            }
            return opened(open, close);
        }
    }
}
