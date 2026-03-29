package tk.funayd.bundleManager.installer;

import tk.funayd.bundleManager.bundle.BundleException;

@FunctionalInterface
public interface BundleFileReader {

    byte[] readFile(ResolvedBundleFile bundleFile) throws BundleException;
}
