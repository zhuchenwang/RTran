/*
 * Copyright (c) 2016 eBay Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ebay.rtran.maven

import java.io.File

import com.ebay.rtran.maven.util.{MavenUtil, MavenModelUtil}
import MavenModelUtil._
import MavenUtil._
import com.typesafe.scalalogging.LazyLogging
import org.apache.maven.model.Dependency
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.util.filter.ExclusionsDependencyFilter
import org.eclipse.aether.{graph => aether}
import com.ebay.rtran.api.{IProjectCtx, IRule, IRuleConfig}

import scala.collection.JavaConversions._
import scala.util.{Failure, Success, Try}


class MavenExcludeDependenciesRule(ruleConfig: MavenExcludeDependenciesRuleConfig)
  extends IRule[MultiModuleMavenModel] with LazyLogging {

  override def transform(model: MultiModuleMavenModel): MultiModuleMavenModel = {
    var changes = Set.empty[File]
    val modules = model.modules map { module =>
      implicit val props = module.properties
      val managedDependencies = module.managedDependencies.values.toList
      // exclude from dependencyManagement
      Option(module.pomModel.getDependencyManagement).map(_.getDependencies.toList) getOrElse List.empty foreach {md =>
        val transitives = MavenUtil.getTransitiveDependencies(resolve(md), managedDependencies)
        val exclusions = ruleConfig.exclusions filter { exclusion =>
          transitives.exists(d => d.getGroupId == exclusion.groupId && d.getArtifactId == exclusion.artifactId)
        }
        if (exclusions.nonEmpty) {
          changes += module.pomFile
          logger.info("{} excluded {} from {} in {}", id, exclusions, md, module.pomFile)
        }
        exclusions foreach (md.addExclusion(_))
      }
      // exclude from the dependencies that has explicit version
      module.pomModel.getDependencies.filter(dep => Option(dep.getVersion).nonEmpty) foreach {dep =>
        val transitives = getTransitiveDependencies(resolve(dep), managedDependencies)
        val exclusions = ruleConfig.exclusions filter { exclusion =>
          transitives.exists(d => d.getGroupId == exclusion.groupId && d.getArtifactId == exclusion.artifactId)
        }
        if (exclusions.nonEmpty) {
          changes += module.pomFile
          logger.info("{} excluded {} from {} in {}", id, exclusions, dep, module.pomFile)
        }
        exclusions foreach (dep.addExclusion(_))
      }
      module
    }
    logger.info("Rule {} was applied to {} files", id, changes.size.toString)
    model.copy(modules = modules)
  }

  override def isEligibleFor(projectCtx: IProjectCtx) = projectCtx.isInstanceOf[MavenProjectCtx]
}

case class MavenExcludeDependenciesRuleConfig(exclusions: Set[SimpleExclusion]) extends IRuleConfig

case class SimpleExclusion(groupId: String, artifactId: String)
