package com.jihulab.llh4gitlab

import com.google.devtools.ksp.symbol.Nullability
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.buildCodeBlock
import org.ktorm.entity.EntitySequence
import org.ktorm.ksp.codegen.TableGenerateContext
import org.ktorm.ksp.codegen.TopLevelFunctionGenerator
import org.ktorm.ksp.codegen.definition.KtormEntityType
import org.ktorm.ksp.codegen.generator.util.MemberNames
import org.ktorm.ksp.codegen.generator.util.withControlFlow

/**
 *
 * <p>Created At 2022/7/23 11:28
 * @author llh
 */
open class SequenceUpdateBatchNonNullGenerator : TopLevelFunctionGenerator {

    private val updateFunc = MemberName("org.ktorm.dsl", "batchUpdate", true)
    override fun generate(context: TableGenerateContext, emitter: (FunSpec) -> Unit) {
        if (context.table.ktormEntityType != KtormEntityType.ANY_KIND_CLASS) {
            context.logger.info("基于接口的模型类不生成此方法")
            return
        }
        val table = context.table
        val primaryKeyColumns = table.columns.filter { it.isPrimaryKey }
        if (primaryKeyColumns.isEmpty()) {
            context.logger.info(
                "skip the entity sequence updateAll method of table ${table.entityClassName} " +
                        "because it does not have a primary key column"
            )
            return
        }
        val nullableInput = table.entityClassDeclaration
            .getAllProperties()
            .filter { Nullability.NULLABLE == it.type.resolve().nullability }
            .map { it.simpleName.asString() }
            .toList()
        context.logger.info(
            "可空属性有： $nullableInput"
        )

        FunSpec.builder("updateNonNull")
            .addKdoc("更新[entities]中每个对象的非空属性值")
            .addKdoc("主键作为约束条件不能为空")
            .receiver(EntitySequence::class.asClassName().parameterizedBy(table.entityClassName, table.tableClassName))
            .addParameter("entities", Iterable::class.asClassName().parameterizedBy(table.entityClassName))
            .returns(IntArray::class.asClassName())
            .addCode(buildCodeBlock {
                addStatement("%M(this)", MemberNames.checkNotModified)
                withControlFlow("return·this.database.%M(%T)", arrayOf(updateFunc, table.tableClassName)) {
                    withControlFlow("for (entity in entities)") {
                        withControlFlow("item") {
                            for (column in table.columns) {
                                val inputProperty = column.entityPropertyName.simpleName
                                if (column.isReferences) {
                                    val primaryKey = column.referencesColumn!!
                                    if (nullableInput.contains(inputProperty) && !column.isPrimaryKey) {
                                        withControlFlow("if(null·!=·entity.%L)", arrayOf(inputProperty)) {
                                            addStatement(
                                                "set(%T.%L,·entity.%L.%L)",
                                                table.tableClassName,
                                                column.tablePropertyName.simpleName,
                                                inputProperty,
                                                primaryKey.entityPropertyName.simpleName
                                            )
                                        }
                                    }
                                } else {
                                    if (nullableInput.contains(inputProperty) && !column.isPrimaryKey) {
                                        withControlFlow("if(null·!=·entity.%L)", arrayOf(inputProperty)) {
                                            addStatement(
                                                "set(%T.%L,·entity.%L)",
                                                table.tableClassName,
                                                column.tablePropertyName.simpleName,
                                                column.entityPropertyName.simpleName
                                            )
                                        }
                                    }
                                }
                            }

                            withControlFlow("where") {
                                primaryKeyColumns.forEachIndexed { index, column ->
                                    if (index == 0) {
                                        val conditionTemperate = if (primaryKeyColumns.size == 1) {
                                            "it.%L·%M·entity.%L%L"
                                        } else {
                                            "(it.%L·%M·entity.%L%L)"
                                        }
                                        addStatement(
                                            conditionTemperate,
                                            column.tablePropertyName.simpleName,
                                            MemberNames.eq,
                                            column.entityPropertyName.simpleName,
                                            if (column.isNullable) "!!" else ""
                                        )
                                    } else {
                                        addStatement(
                                            ".%M(it.%L·%M·entity.%L%L)",
                                            MemberNames.and,
                                            column.tablePropertyName.simpleName,
                                            MemberNames.eq,
                                            column.entityPropertyName.simpleName,
                                            if (column.isNullable) "!!" else ""
                                        )
                                    }
                                }
                            }
                        }

                    }
                }
            })
            .build()
            .run(emitter)
    }
}