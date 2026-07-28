package com.aura.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionPipelineTest {

    @Test
    fun all_contains_six_pipelines() {
        assertEquals(6, ProductionPipeline.all.size)
    }

    @Test
    fun byId_returns_correct_pipeline() {
        val novel = ProductionPipeline.byId("novel")
        assertNotNull(novel)
        assertEquals("Novel", novel!!.name)
    }

    @Test
    fun byId_returns_null_for_unknown() {
        assertNull(ProductionPipeline.byId("nonexistent"))
    }

    @Test
    fun novel_pipeline_has_seven_stages() {
        val novel = ProductionPipeline.novel
        assertEquals(7, novel.stages.size)
    }

    @Test
    fun trailer_pipeline_has_eight_stages() {
        val trailer = ProductionPipeline.trailer
        assertEquals(8, trailer.stages.size)
    }

    @Test
    fun stages_have_valid_dependencies() {
        for (pipeline in ProductionPipeline.all) {
            val stageIds = pipeline.stages.map { it.id }.toSet()
            for (stage in pipeline.stages) {
                for (dep in stage.dependsOn) {
                    assertTrue(
                        "Stage ${stage.id} in ${pipeline.name} depends on $dep which doesn't exist",
                        dep in stageIds,
                    )
                }
            }
        }
    }

    @Test
    fun first_stage_has_no_dependencies() {
        for (pipeline in ProductionPipeline.all) {
            val firstStage = pipeline.stages.first()
            assertTrue(
                "First stage ${firstStage.id} in ${pipeline.name} should have no dependencies",
                firstStage.dependsOn.isEmpty(),
            )
        }
    }

    @Test
    fun last_stage_depends_on_something() {
        for (pipeline in ProductionPipeline.all) {
            val lastStage = pipeline.stages.last()
            assertTrue(
                "Last stage ${lastStage.id} in ${pipeline.name} should have dependencies",
                lastStage.dependsOn.isNotEmpty(),
            )
        }
    }

    @Test
    fun all_stages_have_executor() {
        for (pipeline in ProductionPipeline.all) {
            for (stage in pipeline.stages) {
                assertTrue(
                    "Stage ${stage.id} in ${pipeline.name} has empty executor",
                    stage.executor.isNotBlank(),
                )
            }
        }
    }

    @Test
    fun default_roles_are_populated() {
        for (pipeline in ProductionPipeline.all) {
            assertTrue(
                "Pipeline ${pipeline.name} should have default roles",
                pipeline.defaultRoles.isNotEmpty(),
            )
        }
    }

    @Test
    fun no_circular_dependencies() {
        for (pipeline in ProductionPipeline.all) {
            val visited = mutableSetOf<String>()
            val inStack = mutableSetOf<String>()

            fun hasCycle(stageId: String): Boolean {
                if (stageId in inStack) return true
                if (stageId in visited) return false
                visited.add(stageId)
                inStack.add(stageId)
                val stage = pipeline.stages.firstOrNull { it.id == stageId } ?: return false
                for (dep in stage.dependsOn) {
                    if (hasCycle(dep)) return true
                }
                inStack.remove(stageId)
                return false
            }

            for (stage in pipeline.stages) {
                assertFalse(
                    "Pipeline ${pipeline.name} has circular dependency reachable from ${stage.id}",
                    hasCycle(stage.id),
                )
            }
        }
    }

    @Test
    fun topological_order_is_valid() {
        for (pipeline in ProductionPipeline.all) {
            val positions = pipeline.stages.withIndex().associate { it.value.id to it.index }
            for (stage in pipeline.stages) {
                for (dep in stage.dependsOn) {
                    assertTrue(
                        "Pipeline ${pipeline.name}: ${stage.id} at ${positions[stage.id]} depends on $dep at ${positions[dep]}",
                        (positions[dep] ?: Int.MAX_VALUE) < (positions[stage.id] ?: -1),
                    )
                }
            }
        }
    }

    @Test
    fun image_stages_have_image_output_type() {
        val imageStageIds = setOf("storyboard")
        for (pipeline in ProductionPipeline.all) {
            for (stage in pipeline.stages) {
                if (stage.id in imageStageIds) {
                    assertEquals(
                        "Stage ${stage.id} in ${pipeline.name} should have outputType=\"image\"",
                        "image", stage.outputType,
                    )
                }
            }
        }
    }
}