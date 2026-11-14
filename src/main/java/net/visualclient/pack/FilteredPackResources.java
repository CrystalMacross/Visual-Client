package net.visualclient.pack;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.resources.IoSupplier;

/** Hides every resource of a pack whose category is not ticked in the Pack Mixer. Data packs are not touched. */
public final class FilteredPackResources implements PackResources {
	private final PackResources delegate;
	private final int mask;

	public FilteredPackResources(PackResources delegate, int mask) {
		this.delegate = delegate;
		this.mask = mask;
	}

	private boolean allowed(String path) {
		return (mask & PackCategory.of(path).bit()) != 0;
	}

	@Override
	public IoSupplier<InputStream> getRootResource(String... elements) {
		return delegate.getRootResource(elements);
	}

	@Override
	public IoSupplier<InputStream> getResource(PackType type, Identifier location) {
		if (type == PackType.CLIENT_RESOURCES && !allowed(location.getPath())) {
			return null;
		}
		return delegate.getResource(type, location);
	}

	@Override
	public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
		if (type != PackType.CLIENT_RESOURCES) {
			delegate.listResources(type, namespace, path, output);
			return;
		}
		delegate.listResources(type, namespace, path, (id, supplier) -> {
			if (allowed(id.getPath())) {
				output.accept(id, supplier);
			}
		});
	}

	@Override
	public Set<String> getNamespaces(PackType type) {
		return delegate.getNamespaces(type);
	}

	@Override
	public <T> T getMetadataSection(MetadataSectionType<T> type) throws IOException {
		return delegate.getMetadataSection(type);
	}

	@Override
	public PackLocationInfo location() {
		return delegate.location();
	}

	@Override
	public void close() {
		delegate.close();
	}
}
