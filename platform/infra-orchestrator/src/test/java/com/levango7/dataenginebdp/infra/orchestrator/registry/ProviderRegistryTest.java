package com.levango7.dataenginebdp.infra.orchestrator.registry;

import com.levango7.dataenginebdp.infra.orchestrator.model.EnvironmentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ProviderRegistry} 单元测试。
 *
 * <p>覆盖：注册、查找、注销、列出、禁用判断、缺失环境检测。</p>
 */
class ProviderRegistryTest {

    private ProviderRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ProviderRegistry();
    }

    @Test
    void registerShouldStoreDescriptor() {
        ProviderDescriptor descriptor = sampleDescriptor(EnvironmentType.XINCHANG);
        registry.register(descriptor);

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.lookup(EnvironmentType.XINCHANG)).isEqualTo(descriptor);
    }

    @Test
    void registerShouldOverwriteExisting() {
        ProviderDescriptor first = ProviderDescriptor.builder()
                .environmentType(EnvironmentType.XINCHANG)
                .name("old").baseUrl("http://old:8090").build();
        ProviderDescriptor second = ProviderDescriptor.builder()
                .environmentType(EnvironmentType.XINCHANG)
                .name("new").baseUrl("http://new:8090").build();

        registry.register(first);
        registry.register(second);

        assertThat(registry.lookup(EnvironmentType.XINCHANG).getName()).isEqualTo("new");
    }

    @Test
    void registerShouldRejectNullDescriptor() {
        assertThatThrownBy(() -> registry.register(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void lookupShouldThrowWhenNotRegistered() {
        assertThatThrownBy(() -> registry.lookup(EnvironmentType.XINCHANG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no provider registered");
    }

    @Test
    void lookupShouldThrowWhenDisabled() {
        ProviderDescriptor disabled = ProviderDescriptor.builder()
                .environmentType(EnvironmentType.XINCHANG)
                .name("xinchang").baseUrl("http://x:8090").enabled(false).build();
        registry.register(disabled);

        assertThatThrownBy(() -> registry.lookup(EnvironmentType.XINCHANG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider disabled");
    }

    @Test
    void findShouldReturnEmptyWhenNotRegistered() {
        Optional<ProviderDescriptor> result = registry.find(EnvironmentType.XINCHANG);
        assertThat(result).isEmpty();
    }

    @Test
    void findShouldReturnDescriptorEvenIfDisabled() {
        ProviderDescriptor disabled = ProviderDescriptor.builder()
                .environmentType(EnvironmentType.XINCHANG)
                .name("xinchang").baseUrl("http://x:8090").enabled(false).build();
        registry.register(disabled);

        assertThat(registry.find(EnvironmentType.XINCHANG)).isPresent();
    }

    @Test
    void unregisterShouldRemoveAndReturnDescriptor() {
        ProviderDescriptor descriptor = sampleDescriptor(EnvironmentType.XINCHANG);
        registry.register(descriptor);

        Optional<ProviderDescriptor> removed = registry.unregister(EnvironmentType.XINCHANG);
        assertThat(removed).isPresent().get().isEqualTo(descriptor);
        assertThat(registry.size()).isZero();
    }

    @Test
    void unregisterShouldReturnEmptyWhenNotRegistered() {
        Optional<ProviderDescriptor> removed = registry.unregister(EnvironmentType.XINCHANG);
        assertThat(removed).isEmpty();
    }

    @Test
    void listProvidersShouldReturnAllRegistered() {
        registry.register(sampleDescriptor(EnvironmentType.XINCHANG));
        registry.register(sampleDescriptor(EnvironmentType.BAREMETAL));

        List<ProviderDescriptor> providers = registry.listProviders();
        assertThat(providers).hasSize(2);
    }

    @Test
    void listEnabledProvidersShouldFilterDisabled() {
        registry.register(sampleDescriptor(EnvironmentType.XINCHANG));
        ProviderDescriptor disabled = ProviderDescriptor.builder()
                .environmentType(EnvironmentType.BAREMETAL)
                .name("baremetal").baseUrl("http://x:8091").enabled(false).build();
        registry.register(disabled);

        List<ProviderDescriptor> enabled = registry.listEnabledProviders();
        assertThat(enabled).hasSize(1);
        assertThat(enabled.get(0).getEnvironmentType()).isEqualTo(EnvironmentType.XINCHANG);
    }

    @Test
    void isAvailableShouldReturnTrueOnlyIfRegisteredAndEnabled() {
        registry.register(sampleDescriptor(EnvironmentType.XINCHANG));
        assertThat(registry.isAvailable(EnvironmentType.XINCHANG)).isTrue();
        assertThat(registry.isAvailable(EnvironmentType.BAREMETAL)).isFalse();
    }

    @Test
    void missingEnvironmentsShouldDetectGaps() {
        registry.register(sampleDescriptor(EnvironmentType.XINCHANG));

        List<EnvironmentType> missing = registry.missingEnvironments();
        assertThat(missing).hasSize(6);
        assertThat(missing).doesNotContain(EnvironmentType.XINCHANG);
    }

    @Test
    void missingEnvironmentsShouldReturnEmptyWhenAllRegistered() {
        for (EnvironmentType type : EnvironmentType.values()) {
            registry.register(sampleDescriptor(type));
        }
        assertThat(registry.missingEnvironments()).isEmpty();
    }

    private ProviderDescriptor sampleDescriptor(EnvironmentType type) {
        return ProviderDescriptor.builder()
                .environmentType(type)
                .name("provider-" + type.name())
                .baseUrl("http://provider-" + type.name() + ":8090")
                .enabled(true)
                .build();
    }
}