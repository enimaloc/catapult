package fr.enimaloc.catapult.bgs.proto;

import bgs.protocol.MethodOptionsProto;
import bgs.protocol.ServiceOptionsProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.ExtensionRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

/** Reads bgs.desc and its BGS options: the service hashes and method ids as the descriptors define them. */
final class DescriptorHashes {

    static final String NO_RESPONSE = "bgs.protocol.NO_RESPONSE";

    record Method(String name, int id, String inputType, String outputType) {
    }

    record Service(String fullName, int hash, Map<String, Method> methods) {
    }

    private DescriptorHashes() {
    }

    /** Service by full name (bgs.protocol.friends.v2.client.FriendsService). */
    static Map<String, Service> load() {
        ExtensionRegistry registry = ExtensionRegistry.newInstance();
        registry.add(ServiceOptionsProto.serviceOptions);
        registry.add(MethodOptionsProto.methodOptions);
        FileDescriptorSet set;
        try (InputStream in = DescriptorHashes.class.getResourceAsStream("/bgs.desc")) {
            set = FileDescriptorSet.parseFrom(in, registry);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Service> services = new HashMap<>();
        for (FileDescriptorProto file : set.getFileList()) {
            for (ServiceDescriptorProto service : file.getServiceList()) {
                String fullName = file.getPackage() + "." + service.getName();
                // The client hashes the legacy name (bnet.protocol...) when the descriptor carries one
                String legacy = service.getOptions().getExtension(ServiceOptionsProto.serviceOptions).getDescriptorName();
                Map<String, Method> methods = new HashMap<>();
                for (MethodDescriptorProto m : service.getMethodList()) {
                    int id = m.getOptions().getExtension(MethodOptionsProto.methodOptions).getId();
                    methods.put(m.getName(), new Method(m.getName(), id,
                            m.getInputType().substring(1), m.getOutputType().substring(1)));
                }
                services.put(fullName, new Service(fullName, Fnv1a.hash(legacy.isEmpty() ? fullName : legacy), methods));
            }
        }
        return services;
    }
}
