// Copyright 2011 The MOE Authors All Rights Reserved.

package com.google.devtools.moe.client.project;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.devtools.moe.client.Injector;
import com.google.devtools.moe.client.editors.Editor;
import com.google.devtools.moe.client.editors.ForwardTranslator;
import com.google.devtools.moe.client.editors.IdentityEditor;
import com.google.devtools.moe.client.editors.InverseEditor;
import com.google.devtools.moe.client.editors.InverseRenamingEditor;
import com.google.devtools.moe.client.editors.InverseScrubbingEditor;
import com.google.devtools.moe.client.editors.InverseTranslator;
import com.google.devtools.moe.client.editors.InverseTranslatorStep;
import com.google.devtools.moe.client.editors.PatchingEditor;
import com.google.devtools.moe.client.editors.RenamingEditor;
import com.google.devtools.moe.client.editors.ScrubbingEditor;
import com.google.devtools.moe.client.editors.ShellEditor;
import com.google.devtools.moe.client.editors.Translator;
import com.google.devtools.moe.client.editors.TranslatorPath;
import com.google.devtools.moe.client.editors.TranslatorStep;
import com.google.devtools.moe.client.migrations.MigrationConfig;
import com.google.devtools.moe.client.repositories.Repositories;
import com.google.devtools.moe.client.repositories.RepositoryType;

import java.util.List;
import java.util.Map;

/**
 * Creates a {@link ProjectContext} given a context file name.
 *
 * @author dbentley@google.com (Daniel Bentley)
 */
// TODO(cgruber): Move most of the create logic to ProjectConfig, since they're basically accessors
public abstract class ProjectContextFactory {

  private final Repositories repositories;

  public ProjectContextFactory(Repositories repositories) {
    this.repositories = checkNotNull(repositories);
  }

  /**
   * Makes a ProjectContext for this config filename.
   *
   * @param configFilename the name of the holding the config
   *
   * @return the ProjectContext to be used
   */
  public final ProjectContext create(String configFilename) throws InvalidProject {
    ProjectConfig config = loadConfiguration(configFilename);
    return new AutoValue_ProjectContext(
        config,
        buildRepositories(config),
        buildEditors(config),
        buildTranslators(config),
        buildMigrationConfigs(config));
  }

  /**
   * Supplies a ProjectConfig file given a file-name.
   *
   * <p>Implementors might, for example, include factories that load project configs from
   * a filesystem, or from in-memory strings.
   */
  protected abstract ProjectConfig loadConfiguration(String configFilename) throws InvalidProject;

  //TODO(cgruber): Consider making InvalidProject an unchecked exception to eliminate these loops.
  private ImmutableMap<String, RepositoryType> buildRepositories(ProjectConfig config)
      throws InvalidProject {
    ImmutableMap.Builder<String, RepositoryType> builder = ImmutableMap.builder();
    for (Map.Entry<String, RepositoryConfig> entry : config.getRepositoryConfigs().entrySet()) {
      builder.put(entry.getKey(), repositories.create(entry.getKey(), entry.getValue()));
    }
    return builder.build();
  }

  private ImmutableMap<String, Editor> buildEditors(ProjectConfig config) throws InvalidProject {
    ImmutableMap.Builder<String, Editor> builder = ImmutableMap.builder();
    for (Map.Entry<String, EditorConfig> entry : config.getEditorConfigs().entrySet()) {
      builder.put(
          entry.getKey(), makeEditorFromConfig(entry.getKey(), entry.getValue()));
    }
    return builder.build();
  }

  private ImmutableMap<TranslatorPath, Translator> buildTranslators(ProjectConfig config)
      throws InvalidProject {
    ImmutableMap.Builder<TranslatorPath, Translator> builder = ImmutableMap.builder();
    for (TranslatorConfig translatorConfig : config.getTranslators()) {
      Translator t = makeTranslatorFromConfig(translatorConfig, config);
      TranslatorPath tPath =
          new TranslatorPath(
              translatorConfig.getFromProjectSpace(), translatorConfig.getToProjectSpace());
      builder.put(tPath, t);
    }
    return builder.build();
  }

  private ImmutableMap<String, MigrationConfig> buildMigrationConfigs(ProjectConfig config) {
    ImmutableMap.Builder<String, MigrationConfig> builder = ImmutableMap.builder();
    for (MigrationConfig migrationConfig : config.getMigrationConfigs()) {
      builder.put(migrationConfig.getName(), migrationConfig);
    }
    return builder.build();
  }

  Editor makeEditorFromConfig(String editorName, EditorConfig config) throws InvalidProject {
    switch (config.getType()) {
      case identity:
        return IdentityEditor.makeIdentityEditor(editorName, config);
      case scrubber:
        return ScrubbingEditor.makeScrubbingEditor(editorName, config);
      case patcher:
        return PatchingEditor.makePatchingEditor(editorName, config);
      case shell:
        return ShellEditor.makeShellEditor(editorName, config);
      case renamer:
        return RenamingEditor.makeRenamingEditor(editorName, config);
      default:
        throw new InvalidProject("Invalid editor type: \"%s\"", config.getType());
    }
  }

  Translator makeTranslatorFromConfig(TranslatorConfig transConfig, ProjectConfig projConfig)
      throws InvalidProject {
    if (transConfig.isInverse()) {
      TranslatorConfig otherTrans = findInverseTranslatorConfig(transConfig, projConfig);
      return new InverseTranslator(
          makeStepsFromConfigs(otherTrans.getSteps()),
          makeInverseStepsFromConfigs(otherTrans.getSteps()));
    } else {
      return new ForwardTranslator(makeStepsFromConfigs(transConfig.getSteps()));
    }
  }

  private List<TranslatorStep> makeStepsFromConfigs(List<StepConfig> stepConfigs)
      throws InvalidProject {
    ImmutableList.Builder<TranslatorStep> steps = ImmutableList.builder();
    for (StepConfig sc : stepConfigs) {
      steps.add(
          new TranslatorStep(
              sc.getName(), makeEditorFromConfig(sc.getName(), sc.getEditorConfig())));
    }
    return steps.build();
  }

  private List<InverseTranslatorStep> makeInverseStepsFromConfigs(List<StepConfig> stepConfigs)
      throws InvalidProject {
    ImmutableList.Builder<InverseTranslatorStep> inverseSteps = ImmutableList.builder();
    for (StepConfig sc : Lists.reverse(stepConfigs)) {
      inverseSteps.add(
          new InverseTranslatorStep(
              "inverse_" + sc.getName(),
              makeInverseEditorFromConfig("inverse_" + sc.getName(), sc.getEditorConfig())));
    }
    return inverseSteps.build();
  }

  private TranslatorConfig findInverseTranslatorConfig(
      TranslatorConfig transConfig, ProjectConfig projConfig) throws InvalidProject {
    List<TranslatorConfig> otherTranslators = projConfig.getTranslators();
    for (TranslatorConfig otherTrans : otherTranslators) {
      if (otherTrans.getToProjectSpace().equals(transConfig.getFromProjectSpace())
          && otherTrans.getFromProjectSpace().equals(transConfig.getToProjectSpace())) {
        if (otherTrans.isInverse()) {
          throw new InvalidProject("Can't have mutually inverse translators!");
        }
        return otherTrans;
      }
    }
    throw new InvalidProject(
        "Couldn't find translator whose path is inverse of %s -> %s",
        transConfig.getFromProjectSpace(),
        transConfig.getToProjectSpace());
  }

  private InverseEditor makeInverseEditorFromConfig(String editorName, EditorConfig originalConfig)
      throws InvalidProject {
    switch (originalConfig.getType()) {
      case identity:
        return IdentityEditor.makeIdentityEditor(editorName, originalConfig);
      case renamer:
        return InverseRenamingEditor.makeInverseRenamingEditor(editorName, originalConfig);
      case scrubber:
        // TODO(cgruber) remove Injector.INSTANCE
        return new InverseScrubbingEditor(
            Injector.INSTANCE.cmd(),
            Injector.INSTANCE.fileSystem(),
            Injector.INSTANCE.ui());
      default:
        throw new InvalidProject("Non-invertible editor type: " + originalConfig.getType());
    }
  }
}
