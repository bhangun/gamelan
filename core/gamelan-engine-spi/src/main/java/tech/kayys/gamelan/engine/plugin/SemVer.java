package tech.kayys.gamelan.engine.plugin;

public record SemVer(int major, int minor, int patch)
        implements Comparable<SemVer> {

    @Override
    public int compareTo(SemVer o) {
        if (major != o.major)
            return Integer.compare(major, o.major);
        if (minor != o.minor)
            return Integer.compare(minor, o.minor);
        return Integer.compare(patch, o.patch);
    }
}