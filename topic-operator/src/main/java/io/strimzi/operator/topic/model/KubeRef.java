/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic.model;

import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.operator.common.model.StatusUtils;

import java.util.Objects;

/**
 * Reference to a resource in Kube.
 * Equality is based on {@link #namespace()} and {@link #name()}.
 * {@link #creationTime()} is present to allow disambiguation of multiple KafkaTopics managing the same topic in Kafka.
 *
 * @param namespace     The namespace of the resource
 * @param name          The name of the resource
 * @param creationTime  The creation time of the resource
 */
public record KubeRef(String namespace, String name, long creationTime) {
    /**
     * Create a new KubeRef instance.
     * 
     * @param kt KafkaTopic.
     */
    public KubeRef(KafkaTopic kt) {
        this(kt.getMetadata().getNamespace(), kt.getMetadata().getName(), StatusUtils.isoUtcDatetime(kt.getMetadata().getCreationTimestamp()).toEpochMilli());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KubeRef ref = (KubeRef) o;
        return Objects.equals(namespace, ref.namespace) && Objects.equals(name, ref.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace, name);
    }

    @Override
    public String toString() {
        return "Ref{" +
                "namespace='" + namespace + '\'' +
                ", name='" + name + '\'' +
                '}';
    }
}
