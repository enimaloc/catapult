package fr.enimaloc.catapult.bgs.proto;

import bgs.protocol.MethodOptionsProto;
import bgs.protocol.ServiceOptionsProto;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.TextFormat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every service of bgs.desc with its wire hash, its methods and their message types, for calls built at runtime
 * (the admin's free BGS call). Bodies go through {@link DynamicMessage}, so any method of the descriptors can be
 * encoded from text and decoded back, not only the ones {@link BgsServices} knows.
 */
public final class BgsCatalog {

    static final String NO_RESPONSE = "bgs.protocol.NO_RESPONSE";

    /** {@code noResponse}: the method's output type is {@code NO_RESPONSE}, nobody answers it. */
    public record MethodInfo(String service, int serviceHash, String name, int id, Descriptor input,
                             Descriptor output, boolean noResponse) {
    }

    /** Methods sorted by id. */
    public record ServiceInfo(String fullName, int hash, List<MethodInfo> methods) {
    }

    private static final class Holder {
        static final BgsCatalog INSTANCE = load();
    }

    private final List<ServiceInfo> services;
    private final Map<String, ServiceInfo> byName = new HashMap<>();
    private final Map<Integer, ServiceInfo> byHash = new HashMap<>();

    private BgsCatalog(List<ServiceInfo> services) {
        this.services = List.copyOf(services);
        for (ServiceInfo service : services) {
            byName.put(service.fullName(), service);
            byHash.put(service.hash(), service);
        }
    }

    public static BgsCatalog instance() {
        return Holder.INSTANCE;
    }

    /** Sorted by full name. */
    public List<ServiceInfo> services() {
        return services;
    }

    public Optional<MethodInfo> find(String serviceFullName, String methodName) {
        return Optional.ofNullable(byName.get(serviceFullName)).flatMap(s -> s.methods().stream()
                .filter(m -> m.name().equals(methodName)).findFirst());
    }

    public Optional<MethodInfo> find(int serviceHash, int methodId) {
        return Optional.ofNullable(byHash.get(serviceHash)).flatMap(s -> s.methods().stream()
                .filter(m -> m.id() == methodId).findFirst());
    }

    /** Parses protobuf text format into the wire bytes of {@code type}. */
    public static ByteString encode(Descriptor type, String text) throws TextFormat.ParseException {
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(type);
        TextFormat.getParser().merge(text == null ? "" : text, builder);
        return builder.buildPartial().toByteString();
    }

    /** The body as protobuf text, or empty when it does not parse as {@code type}. */
    public static Optional<String> decode(Descriptor type, ByteString body) {
        try {
            return Optional.of(TextFormat.printer().printToString(DynamicMessage.parseFrom(type, body)));
        } catch (InvalidProtocolBufferException e) {
            return Optional.empty();
        }
    }

    private static BgsCatalog load() {
        ExtensionRegistry registry = ExtensionRegistry.newInstance();
        registry.add(ServiceOptionsProto.serviceOptions);
        registry.add(MethodOptionsProto.methodOptions);
        FileDescriptorSet set;
        try (InputStream in = BgsCatalog.class.getResourceAsStream("/bgs.desc")) {
            set = FileDescriptorSet.parseFrom(in, registry);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, FileDescriptorProto> protos = new HashMap<>();
        set.getFileList().forEach(f -> protos.put(f.getName(), f));
        Map<String, FileDescriptor> built = new HashMap<>();
        built.put("google/protobuf/descriptor.proto", DescriptorProtos.getDescriptor());

        List<ServiceInfo> services = new ArrayList<>();
        for (FileDescriptorProto file : set.getFileList()) {
            if (file.getServiceCount() == 0) {
                continue;
            }
            FileDescriptor fd = build(file.getName(), protos, built);
            for (int i = 0; i < file.getServiceCount(); i++) {
                services.add(service(file, file.getService(i), fd.getServices().get(i)));
            }
        }
        services.sort(Comparator.comparing(ServiceInfo::fullName));
        return new BgsCatalog(services);
    }

    private static ServiceInfo service(FileDescriptorProto file, ServiceDescriptorProto proto,
                                       Descriptors.ServiceDescriptor descriptor) {
        String fullName = file.getPackage() + "." + proto.getName();
        // The client hashes the legacy name (bnet.protocol...) when the descriptor carries one
        String legacy = proto.getOptions().getExtension(ServiceOptionsProto.serviceOptions).getDescriptorName();
        int hash = Fnv1a.hash(legacy.isEmpty() ? fullName : legacy);
        List<MethodInfo> methods = new ArrayList<>();
        for (int i = 0; i < proto.getMethodCount(); i++) {
            MethodDescriptorProto m = proto.getMethod(i);
            Descriptors.MethodDescriptor md = descriptor.getMethods().get(i);
            int id = m.getOptions().getExtension(MethodOptionsProto.methodOptions).getId();
            methods.add(new MethodInfo(fullName, hash, m.getName(), id, md.getInputType(), md.getOutputType(),
                    NO_RESPONSE.equals(m.getOutputType().substring(1))));
        }
        methods.sort(Comparator.comparingInt(MethodInfo::id));
        return new ServiceInfo(fullName, hash, List.copyOf(methods));
    }

    private static FileDescriptor build(String name, Map<String, FileDescriptorProto> protos,
                                        Map<String, FileDescriptor> built) {
        FileDescriptor done = built.get(name);
        if (done != null) {
            return done;
        }
        FileDescriptorProto proto = protos.get(name);
        if (proto == null) {
            throw new IllegalStateException("bgs.desc misses " + name);
        }
        FileDescriptor[] deps = proto.getDependencyList().stream().map(d -> build(d, protos, built))
                .toArray(FileDescriptor[]::new);
        try {
            FileDescriptor fd = FileDescriptor.buildFrom(proto, deps);
            built.put(name, fd);
            return fd;
        } catch (DescriptorValidationException e) {
            throw new IllegalStateException("Invalid descriptor in bgs.desc: " + name, e);
        }
    }
}
