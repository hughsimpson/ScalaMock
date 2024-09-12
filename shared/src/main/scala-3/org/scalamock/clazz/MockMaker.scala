// Copyright (c) ScalaMock Contributors (https://github.com/paulbutcher/ScalaMock/graphs/contributors)
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.

package org.scalamock.clazz

import org.scalamock.context.MockContext
import scala.quoted.*
import scala.reflect.Selectable

@scala.annotation.experimental
private[clazz] object MockMaker:
  val MockDefaultNameValName = "mock$special$mockName"

  def instance[T: Type](mockType: MockType, ctx: Expr[MockContext], name: Option[Expr[String]])(using quotes: Quotes): Expr[T & Selectable] =
    val utils = Utils(using quotes)
    import utils.quotes.reflect.*
    val tpe = TypeRepr.of[T]
    val isTrait = tpe.dealias.typeSymbol.flags.is(Flags.Trait)
    val isJavaClass = tpe.classSymbol.exists(sym => sym.flags.is(Flags.JavaDefined) && !sym.flags.is(Flags.Trait) && !sym.flags.is(Flags.Abstract))

    if (isJavaClass)
      report.errorAndAbort("Can't mock a java class due to https://github.com/lampepfl/dotty/issues/18694. Extend it manually with scala and then mock")

    def asParent(tree: TypeTree): TypeTree | Term =
      val constructorFieldsFilledWithNulls: List[List[Term]] =
        // we don't care about any of the parameters, and just want to pass null, since all interesting parts will be mocked.
        // that's simpler said than done, because if type of the argument is a primitive
        // then null needs to be cast to that primitive type.
        // the type could be a generic type though, which could resolve to a primitive, so we need to find the type the param
        // should resolve to, and cast it if needed.
        val paramsMap = {
          val abstractParams = tree.tpe.dealias.typeSymbol.primaryConstructor.paramSymss.filter(_.exists(_.isType)).flatten
          val resolvedParams = tpe.widen match
            case AppliedType(_, params) => params
            case _ => Nil
          abstractParams.zip(resolvedParams).toMap
        }

        tree.tpe.dealias.typeSymbol.primaryConstructor.paramSymss
          .filterNot(_.exists(_.isType))
          .map(_.map{ sym =>
            val target = paramsMap.getOrElse(sym.info.typeSymbol, sym.info).widen.dealias
            if target <:< TypeRepr.of[AnyVal] then
              Select.unique('{null}.asTerm, "asInstanceOf").appliedToType(target)
            else
              '{null}.asTerm
          })

      if constructorFieldsFilledWithNulls.forall(_.isEmpty) then
        tree
      else
        Select(
          New(TypeIdent(tree.tpe.typeSymbol)),
          tree.tpe.typeSymbol.primaryConstructor
        ).appliedToTypes(tree.tpe.typeArgs)
         .appliedToArgss(constructorFieldsFilledWithNulls)



    val parents =
      if isTrait then
        List(TypeTree.of[Object], asParent(TypeTree.of[T]), TypeTree.of[Selectable])
      else
        List(asParent(TypeTree.of[T]), TypeTree.of[Selectable])


    val mockableDefinitions = utils.MockableDefinitions(tpe)

    def createDefaultMockNameSymbol(classSymbol: Symbol) =
      Symbol.newVal(classSymbol, MockDefaultNameValName, TypeRepr.of[String], Flags.EmptyFlags, Symbol.noSymbol)

    val classSymbol: Symbol = Symbol.newClass(
      parent = Symbol.spliceOwner,
      name = "$anon",
      parents = parents.map {
        case term: Term => term.tpe
        case tree: TypeTree => tree.tpe
      },
      decls = classSymbol => createDefaultMockNameSymbol(classSymbol) :: mockableDefinitions.flatMap { definition =>
        val mockFunctionClassSym =
          Symbol.classSymbol(s"org.scalamock.function.${mockType}Function${definition.parameterTypes.length}")

        val mockFunctionSym =
          Symbol.newVal(
            parent = classSymbol,
            name = definition.mockValName,
            tpe = TypeTree.ref(mockFunctionClassSym).tpe.appliedTo(definition.prepareTypesFor(classSymbol).map(_.tpe)),
            flags = Flags.EmptyFlags,
            privateWithin = Symbol.noSymbol
          )

        val overrideSym =
          if definition.symbol.isValDef then
            Symbol.newVal(
              parent = classSymbol,
              name = definition.symbol.name,
              tpe = definition.tpeWithSubstitutedInnerTypesFor(classSymbol),
              flags = Flags.Override,
              privateWithin = Symbol.noSymbol
            )
          else
            Symbol.newMethod(
              parent = classSymbol,
              name = definition.symbol.name,
              tpe = definition.tpeWithSubstitutedInnerTypesFor(classSymbol),
              flags = Flags.Override,
              privateWithin = Symbol.noSymbol
            )

        if (definition.symbol.flags.is(Flags.Artifact)) Nil
        else List(mockFunctionSym, overrideSym)
      },
      selfType = None
    )
    val defaultMockNameSymbol = classSymbol.declaredField(MockDefaultNameValName)

    val defaultMockName = ValDef(
      defaultMockNameSymbol,
      Some(
        name
          .getOrElse('{ ${ctx}.generateMockDefaultName(${ Expr(mockType.toString.toLowerCase) }).name })
          .asTerm
      )
    )

    val classDef: ClassDef =
      ClassDef(
        cls = classSymbol,
        parents = parents,
        body = defaultMockName :: mockableDefinitions.flatMap {
          case definition if definition.symbol.flags.is(Flags.Artifact) => Nil
          case definition =>
          val mockFunctionValDef: ValDef =
            val valSym = classSymbol.declaredField(definition.mockValName)
            val mockFunctionClassSymbol = valSym.typeRef.classSymbol.get
            val mockFunctionUniqueName = '{
              scala.Symbol(
                Predef.augmentString("<%s> %s%s.%s%s")
                  .format(
                    ${ Ref(defaultMockNameSymbol).asExpr },
                    ${ Expr(tpe.typeSymbol.name) },
                    ${ Expr(if (tpe.typeArgs.isEmpty) "" else "[%s]".format(tpe.typeArgs.map(_.show(using Printer.TypeReprShortCode)).mkString(","))) },
                    ${ Expr(definition.symbol.name) },
                    ${ Expr {
                      definition.tpe match
                        case PolyType(params, _, _) => params.mkString("[", ",", "]")
                        case _ => ""
                    }
                    }
                  )
              )
            }
            ValDef(
              symbol = valSym,
              rhs = Some(
                Apply(
                  TypeApply(
                    Select(
                      New(TypeIdent(mockFunctionClassSymbol)),
                      mockFunctionClassSymbol.primaryConstructor
                    ),
                    definition.prepareTypesFor(classSymbol)
                  ),
                  List(ctx.asTerm, mockFunctionUniqueName.asTerm)
                )
              )
            )

          val definitionOverride =
            if (definition.symbol.isValDef)
              ValDef(definition.symbol.overridingSymbol(classSymbol), Some('{ null }.asTerm))
            else
              DefDef(
                definition.symbol.overridingSymbol(classSymbol),
                { args =>
                  Some(
                    TypeApply(
                      Select.unique(
                        Apply(
                          Select.unique(Ref(mockFunctionValDef.symbol), "apply"),
                          args.flatten.collect { case t: Term => Select.unique(t, "asInstanceOf") }
                        ),
                        "asInstanceOf"
                      ),
                      definition.tpe
                        .resolveParamRefs(definition.resTypeWithInnerTypesOverrideFor(classSymbol), args)
                        .asType match { case '[t] => List(TypeTree.of[t]) }
                    )
                  )
                }
              )
          List(mockFunctionValDef, definitionOverride)
        }
      )

    Block(
      List(classDef),
      Typed(Apply(Select(New(TypeIdent(classSymbol)), classSymbol.primaryConstructor), Nil), TypeTree.of[T & Selectable])
    ).asExprOf[T & Selectable]