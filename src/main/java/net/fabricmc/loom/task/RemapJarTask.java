/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.task;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.common.base.Preconditions;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.tasks.Jar;
import org.zeroturnaround.zip.ZipUtil;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.build.JarRemapper;
import net.fabricmc.loom.build.MixinRefmapHelper;
import net.fabricmc.loom.build.NestedJars;
import net.fabricmc.loom.configuration.accesswidener.AccessWidenerJarProcessor;
import net.fabricmc.loom.configuration.providers.mappings.MappingsProvider;
import net.fabricmc.loom.util.TinyRemapperMappingsHelper;
import net.fabricmc.loom.util.ZipReprocessorUtil;
import net.fabricmc.loom.util.gradle.GradleSupport;
import net.fabricmc.stitch.util.Pair;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;

public class RemapJarTask extends Jar {
	private final RegularFileProperty input;
	private final Property<Boolean> addNestedDependencies;
	private final Property<Boolean> remapAccessWidener;
	private final List<Action<TinyRemapper.Builder>> remapOptions = new ArrayList<>();
	public JarRemapper jarRemapper;
	private FileCollection classpath;

	public RemapJarTask() {
		super();
		input = GradleSupport.getfileProperty(getProject());
		addNestedDependencies = getProject().getObjects().property(Boolean.class);
		remapAccessWidener = getProject().getObjects().property(Boolean.class);
		// false by default, I have no idea why I have to do it for this property and not the other one
		remapAccessWidener.set(false);
	}

	@TaskAction
	public void doTask() throws Throwable {
		if (jarRemapper == null) {
			doSingleRemap();
		} else {
			scheduleRemap();
		}
	}

	public void doSingleRemap() throws Throwable {
		Project project = getProject();
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		Path input = this.getInput().getAsFile().get().toPath();
		Path output = this.getArchivePath().toPath();

		if (!Files.exists(input)) {
			throw new FileNotFoundException(input.toString());
		}

		MappingsProvider mappingsProvider = extension.getMappingsProvider();

		String fromM = "named";
		String toM = "intermediary";

		Path[] classpath = getRemapClasspath();

		TinyRemapper.Builder remapperBuilder = TinyRemapper.newRemapper();

		remapperBuilder = remapperBuilder.withMappings(TinyRemapperMappingsHelper.create(mappingsProvider.getMappings(), fromM, toM, false));

		for (File mixinMapFile : extension.getAllMixinMappings()) {
			if (mixinMapFile.exists()) {
				remapperBuilder = remapperBuilder.withMappings(TinyUtils.createTinyMappingProvider(mixinMapFile.toPath(), fromM, toM));
			}
		}

		// Apply any requested options to tiny remapper
		for (Action<TinyRemapper.Builder> remapOption : this.remapOptions) {
			remapOption.execute(remapperBuilder);
		}

		project.getLogger().lifecycle(":remapping " + input.getFileName());

		StringBuilder rc = new StringBuilder("Remap classpath: ");

		for (Path p : classpath) {
			rc.append("\n - ").append(p.toString());
		}

		project.getLogger().debug(rc.toString());

		TinyRemapper remapper = remapperBuilder.build();

		try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(output).build()) {
			outputConsumer.addNonClassFiles(input);
			remapper.readClassPath(classpath);
			remapper.readInputs(input);
			remapper.apply(outputConsumer);
		} catch (Exception e) {
			remapper.finish();
			throw new RuntimeException("Failed to remap " + input + " to " + output, e);
		}

		if (getRemapAccessWidener().getOrElse(false) && extension.accessWidener != null) {
			extension.getJarProcessorManager().getByType(AccessWidenerJarProcessor.class).remapAccessWidener(output, remapper.getRemapper());
		}

		remapper.finish();

		if (!Files.exists(output)) {
			throw new RuntimeException("Failed to remap " + input + " to " + output + " - file missing!");
		}

		if (MixinRefmapHelper.addRefmapName(extension.getRefmapName(), extension.getMixinJsonVersion(), output)) {
			project.getLogger().debug("Transformed mixin reference maps in output JAR!");
		}

		if (getAddNestedDependencies().getOrElse(false)) {
			if (NestedJars.addNestedJars(project, output)) {
				project.getLogger().debug("Added nested jar paths to mod json");
			}
		}

		if (isReproducibleFileOrder() || isPreserveFileTimestamps()) {
			ZipReprocessorUtil.reprocessZip(output.toFile(), isReproducibleFileOrder(), isPreserveFileTimestamps());
		}
	}

	public void scheduleRemap() throws Throwable {
		Project project = getProject();
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		Path input = this.getInput().getAsFile().get().toPath();
		Path output = this.getArchivePath().toPath();

		if (!Files.exists(input)) {
			throw new FileNotFoundException(input.toString());
		}

		MappingsProvider mappingsProvider = extension.getMappingsProvider();

		String fromM = "named";
		String toM = "intermediary";

		if (extension.isRootProject()) {
			jarRemapper.addToClasspath(getRemapClasspath());

			jarRemapper.addMappings(TinyRemapperMappingsHelper.create(mappingsProvider.getMappings(), fromM, toM, false));
		}

		for (File mixinMapFile : extension.getAllMixinMappings()) {
			if (mixinMapFile.exists()) {
				jarRemapper.addMappings(TinyUtils.createTinyMappingProvider(mixinMapFile.toPath(), fromM, toM));
			}
		}

		// Add remap options to the jar remapper
		jarRemapper.addOptions(this.remapOptions);

		jarRemapper.scheduleRemap(input, output)
				.supplyAccessWidener((remapData, remapper) -> {
					if (getRemapAccessWidener().getOrElse(false) && extension.accessWidener != null) {
						AccessWidenerJarProcessor accessWidenerJarProcessor = extension.getJarProcessorManager().getByType(AccessWidenerJarProcessor.class);
						byte[] data;

						try {
							data = accessWidenerJarProcessor.getRemappedAccessWidener(remapper);
						} catch (IOException e) {
							throw new RuntimeException("Failed to remap access widener");
						}

						String awPath = accessWidenerJarProcessor.getAccessWidenerPath(remapData.input);
						Preconditions.checkNotNull(awPath, "Failed to find accessWidener in fabric.mod.json: " + remapData.input);

						return Pair.of(awPath, data);
					}

					return null;
				})
				.complete((data, accessWidener) -> {
					if (!Files.exists(output)) {
						throw new RuntimeException("Failed to remap " + input + " to " + output + " - file missing!");
					}

					if (MixinRefmapHelper.addRefmapName(extension.getRefmapName(), extension.getMixinJsonVersion(), output)) {
						project.getLogger().debug("Transformed mixin reference maps in output JAR!");
					}

					if (getAddNestedDependencies().getOrElse(false)) {
						if (NestedJars.addNestedJars(project, output)) {
							project.getLogger().debug("Added nested jar paths to mod json");
						}
					}

					if (accessWidener != null) {
						boolean replaced = ZipUtil.replaceEntry(data.output.toFile(), accessWidener.getLeft(), accessWidener.getRight());
						Preconditions.checkArgument(replaced, "Failed to remap access widener");
					}
				});
	}

	private Path[] getRemapClasspath() {
		FileCollection files = this.classpath;

		if (files == null) {
			files = getProject().getConfigurations().getByName("compileClasspath");
		}

		return files.getFiles().stream()
				.map(File::toPath)
				.filter(Files::exists)
				.toArray(Path[]::new);
	}

	@InputFile
	public RegularFileProperty getInput() {
		return input;
	}

	@Input
	public Property<Boolean> getAddNestedDependencies() {
		return addNestedDependencies;
	}

	@Input
	public Property<Boolean> getRemapAccessWidener() {
		return remapAccessWidener;
	}

	public void remapOptions(Action<TinyRemapper.Builder> action) {
		this.remapOptions.add(action);
	}

	public RemapJarTask classpath(FileCollection collection) {
		if (this.classpath == null) {
			this.classpath = collection;
		} else {
			this.classpath = this.classpath.plus(collection);
		}

		return this;
	}
}
