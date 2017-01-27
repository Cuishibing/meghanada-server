package meghanada.cache;

import com.google.common.base.Stopwatch;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import meghanada.config.Config;
import meghanada.reflect.CandidateUnit;
import meghanada.reflect.ClassIndex;
import meghanada.reflect.MemberDescriptor;
import meghanada.reflect.asm.ASMReflector;
import meghanada.reflect.asm.CachedASMReflector;
import meghanada.reflect.asm.InheritanceInfo;
import meghanada.utils.ClassName;
import meghanada.utils.ClassNameUtils;
import meghanada.utils.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static meghanada.utils.FunctionUtils.wrapIO;
import static meghanada.utils.FunctionUtils.wrapIOConsumer;

public class MemberCacheLoader extends CacheLoader<String, List<MemberDescriptor>> implements RemovalListener<String, List<MemberDescriptor>> {

    private static final Logger log = LogManager.getLogger(MemberCacheLoader.class);
    private static final String CLASS_CHECKSUM = "class_checksum.dat";
    private final Map<String, File> classFileMap;
    private final Map<ClassIndex, File> reflectIndex;
    private final String javaVersion;
    private final Map<String, String> cacheChecksum;
    private final File cacheChecksumFile;

    public MemberCacheLoader(Map<String, File> classFileMap, Map<ClassIndex, File> reflectIndex) {
        this.classFileMap = classFileMap;
        this.reflectIndex = reflectIndex;
        Config config = Config.load();
        this.javaVersion = Config.load().getJavaVersion();

        this.cacheChecksumFile = getChecksumFile();
        if (this.cacheChecksumFile.exists()) {
            this.cacheChecksum = new ConcurrentHashMap<>(this.readCacheChecksum(this.cacheChecksumFile));
        } else {
            this.cacheChecksum = new ConcurrentHashMap<>(64);
        }
    }

    private File getChecksumFile() {
        final String settingDir = Config.load().getProjectSettingDir();
        final File settingFile = new File(settingDir);
        if (!settingFile.exists()) {
            settingFile.mkdirs();
        }
        return new File(settingFile, CLASS_CHECKSUM);
    }

    @Override
    public List<MemberDescriptor> load(final String className) throws IOException {

        final ClassName cn = new ClassName(className);
        final String fqcn = cn.getName();
        final String path = ClassNameUtils.replace(fqcn, ".", File.separator);
        final Config config = Config.load();
        final File cacheFilePath = new File(config.getProjectCacheDir(),
                this.javaVersion + File.pathSeparator + "member" + File.pathSeparator + path + ".dat");

        File classFile = this.classFileMap.get(fqcn);
        if (classFile == null) {
            // try inner class
            classFile = this.classFileMap.get(ClassNameUtils.replaceInnerMark(fqcn));
            if (classFile == null) {
                log.debug("Missing FQCN:{}'s file is null", fqcn);
                return Collections.emptyList();
            }
        }

        @SuppressWarnings("unchecked")
        final List<MemberDescriptor> cachedResult = this.getCachedMemberDescriptors(fqcn, cacheFilePath, classFile);
        if (cachedResult != null) {
            return cachedResult;
        }
        final String initName = ClassNameUtils.getSimpleName(fqcn);

        final Stopwatch stopwatch = Stopwatch.createStarted();
        final ASMReflector asmReflector = ASMReflector.getInstance();
        final InheritanceInfo info = asmReflector.getReflectInfo(reflectIndex, fqcn);
        final List<MemberDescriptor> list = asmReflector.reflectAll(info);

        final List<MemberDescriptor> memberDescriptors = list.stream().filter(md -> {
            if (md.matchType(CandidateUnit.MemberType.CONSTRUCTOR)) {
                final String name = ClassNameUtils.getSimpleName(md.getName());
                return name.equals(initName);
            }
            return true;
        }).collect(Collectors.toList());

        // this.writeFileCache(fqcn, memberDescriptors);
        log.trace("load fqcn:{} elapsed:{}", fqcn, stopwatch.stop());
        GlobalCache.getInstance().writeCacheToFile(this.cacheChecksumFile, this.cacheChecksum);
        return memberDescriptors;
    }

    private List<MemberDescriptor> getCachedMemberDescriptors(final String fqcn, final File cacheFilePath, final File file) throws IOException {
        if (file.exists()) {
            final String fileName = file.getName();
            if (file.isFile() && fileName.endsWith(".class")) {
                final String md5sum = FileUtils.md5sum(file);
                final String filePath = file.getCanonicalPath();
                if (this.cacheChecksum.containsKey(filePath)) {
                    if (this.cacheChecksum.get(filePath).equals(md5sum)) {
                        // not modified
                        final List<MemberDescriptor> cachedResult = this.loadFromCache(cacheFilePath);
                        if (cachedResult != null) {
                            return cachedResult;
                        }
                    } else {
                        this.cacheChecksum.put(filePath, md5sum);
                    }
                } else {
                    this.cacheChecksum.put(filePath, md5sum);
                }
            } else if (file.isFile() && fileName.endsWith(".jar") && !fileName.contains("SNAPSHOT")) {
                final List<MemberDescriptor> cachedResult = this.loadFromCache(cacheFilePath);
                if (cachedResult != null) {
                    return cachedResult;
                }
            } else if (file.isFile() && fileName.endsWith(".jar") && fileName.contains("SNAPSHOT")) {
                // skip
                return null;
            } else {
                // Dir
                final File classFile = new File(file, ClassNameUtils.replace(fqcn, ".", File.separator) + ".class");
                if (classFile.exists()) {
                    final String md5sum = FileUtils.md5sum(classFile);
                    final String classFilePath = classFile.getCanonicalPath();
                    if (this.cacheChecksum.containsKey(classFilePath)) {
                        if (this.cacheChecksum.get(classFilePath).equals(md5sum)) {
                            // not modified
                            final List<MemberDescriptor> cachedResult = this.loadFromCache(cacheFilePath);
                            if (cachedResult != null) {
                                return cachedResult;
                            }
                        } else {
                            this.cacheChecksum.put(classFilePath, md5sum);
                        }
                    } else {
                        this.cacheChecksum.put(classFilePath, md5sum);
                    }
                } else {
                    log.warn("not exists:{}", classFile);
                }
            }
        }
        return null;
    }

    private void writeFileCache(final String fqcn, final List<MemberDescriptor> list) {
        final CachedASMReflector reflector = CachedASMReflector.getInstance();
        final Config config = Config.load();
        reflector.containsClassIndex(fqcn)
                .map(wrapIO(classIndex -> {
                    reflector.writeCache(classIndex, list, new File(config.getProjectCacheDir()));
                    return true;
                }))
                .orElseGet(() -> {
                    final String fqcn2 = ClassNameUtils.replaceInnerMark(fqcn);
                    reflector.containsClassIndex(fqcn2)
                            .ifPresent(wrapIOConsumer(classIndex -> {
                                reflector.writeCache(classIndex, list, new File(config.getProjectCacheDir()));
                            }));
                    return true;
                });

    }

    @SuppressWarnings("unchecked")
    private List<MemberDescriptor> loadFromCache(final File cacheFile) throws FileNotFoundException {
        if (cacheFile.exists()) {
            return GlobalCache.getInstance().readCacheFromFile(cacheFile, ArrayList.class);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readCacheChecksum(final File inFile) {
        return GlobalCache.getInstance().readCacheFromFile(inFile, HashMap.class);
    }

    @Override
    public void onRemoval(final RemovalNotification<String, List<MemberDescriptor>> notification) {
        final RemovalCause cause = notification.getCause();
        if (cause.equals(RemovalCause.EXPIRED) ||
                cause.equals(RemovalCause.SIZE) ||
                cause.equals(RemovalCause.REPLACED)) {
            final String key = notification.getKey();
            final List<MemberDescriptor> value = notification.getValue();
            this.writeFileCache(key, value);
        } else if (cause.equals(RemovalCause.EXPLICIT)) {
        }
    }
}