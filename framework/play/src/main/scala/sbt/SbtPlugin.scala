package sbt

import Keys._
import jline._

import play.core._

object PlayProject extends Plugin {
    
    
    
    // ----- We need this later
    
    private val consoleReader = new jline.ConsoleReader
    
    private def waitForKey() = {
        consoleReader.getTerminal.disableEcho() 
        def waitEOF {
            consoleReader.readVirtualKey() match {
                case 4 => // STOP
                case 11 => consoleReader.clearScreen(); waitEOF
                case 10 => println(); waitEOF
                case _ => waitEOF
            }
            
        }
        waitEOF
        consoleReader.getTerminal.enableEcho()
    }
    
    
    
    // ----- Exceptions
    
    case class CompilationException(problem:xsbti.Problem) extends PlayException(
        "Erro de compilação",
        "O arquivo %s não pode ser compilado. O erro é: %s".format(
            problem.position.sourcePath.getOrElse("(arquivo fonte não definido?)"),
            problem.message
        )
    ) with ExceptionSource {
        def line = problem.position.line.map(m => Some(m.asInstanceOf[Int])).getOrElse(None)
        def position = problem.position.pointer.map(m => Some(m.asInstanceOf[Int])).getOrElse(None)
        def file = problem.position.sourceFile.map(m => Some(m)).getOrElse(None)
    }

    case class TemplateCompilationException(source:File, message:String, atLine:Int, column:Int) extends PlayException(
        "Erro de compilação",
        "O arquivo %s não pode ser compilado. O erro é: %s".format(
            source.getAbsolutePath,
            message
        )
    ) with ExceptionSource {
        def line = Some(atLine)
        def position = Some(column)
        def file = Some(source)
    }
    
    case class RoutesCompilationException(source:File, message:String, atLine:Option[Int], column:Option[Int]) extends PlayException(
        "Erro de compilação",
        "O arquivo %s não pode ser compilado. O erro é: %s".format(
            source.getAbsolutePath,
            message
        )
    ) with ExceptionSource {
        def line = atLine
        def position = column
        def file = Some(source)
    }
    
    
    
    // ----- Keys
    
    val distDirectory = SettingKey[File]("play-dist")
    val playResourceDirectories = SettingKey[Seq[File]]("play-resource-directories")
    val confDirectory = SettingKey[File]("play-conf")

    
    // ----- Play specific tasks
        
    val playCompileEverything = TaskKey[Seq[sbt.inc.Analysis]]("play-compile-everything") 
    val playCompileEverythingTask = (state, thisProjectRef) flatMap { (s,r) =>
        Defaults.inAllDependencies(r, (compile in Compile).task, Project structure s).join
    }
    
    val playPackageEverything = TaskKey[Seq[File]]("play-package-everything") 
    val playPackageEverythingTask = (state, thisProjectRef) flatMap { (s,r) =>
        Defaults.inAllDependencies(r, (packageBin in Compile).task, Project structure s).join
    }
    
    val playCopyResources = TaskKey[Seq[(File,File)]]("play-copy-resources") 
    val playCopyResourcesTask = (baseDirectory, playResourceDirectories, classDirectory in Compile, cacheDirectory, streams) map { (b,r,t,c,s) =>
        val cacheFile = c / "copy-resources"
        val mappings = r.map( _ *** ).reduceLeft(_ +++ _) x rebase(b, t)
        s.log.debug("Copie os mapeamentos dos recursos do play: " + mappings.mkString("\n\t","\n\t",""))
        Sync(cacheFile)(mappings)
        mappings
    }
        
    val playReload = TaskKey[sbt.inc.Analysis]("play-reload")
    val playReloadTask = (playCopyResources, playCompileEverything) map { (_,analysises) =>
        analysises.reduceLeft(_ ++ _)
    }
    
    val dist = TaskKey[File]("dist", "Construir o pacote de aplicação standalone")
    val distTask = (baseDirectory, playPackageEverything, dependencyClasspath in Runtime, target, normalizedName, version) map { (root, packaged, dependencies, target, id, version) =>

        import sbt.NameFilter._

        val dist = root / "dist"
        val packageName = id + "-" + version
        val zip = dist / (packageName + ".zip")

        IO.delete(dist)
        IO.createDirectory(dist)

        val libs = {
            dependencies.filter(_.data.ext == "jar").map { dependency =>
                dependency.data -> (packageName + "/lib/" + (dependency.metadata.get(AttributeKey[ModuleID]("module")).map { module =>
                    module.organization + "." + module.name + "-" + module.revision + ".jar"
                }.getOrElse(dependency.data.getName)))
            } ++ packaged.map(jar => jar -> (packageName + "/lib/" + jar.getName))
        } 
        
        val run = target / "run"
        IO.write(run, 
            """java "$@" -cp "`dirname $0`/lib/*" play.core.server.NettyServer `dirname $0`""" /**/
        )
        ("chmod a+x " + run.getAbsolutePath) !
        val scripts = Seq(run -> (packageName + "/run"))

        IO.zip(libs ++ scripts, zip)
        IO.delete(run)
        
        println()
        println("Sua aplicação está pronta em " + zip.getCanonicalPath)
        println()

        zip
    }
    
    
    
    // ----- Source generators
    
    val RouteFiles = (confDirectory:File, generatedDir:File) => {
        import play.core.Router.RoutesCompiler._
        
        (generatedDir ** "routes_*").get.map(GeneratedSource(_)).foreach(_.sync())
        try {
            (confDirectory * "routes").get.foreach { routesFile =>
                compile(routesFile, generatedDir)
            }
        } catch {
            case RoutesCompilationError(source, message, line, column) => {
                throw RoutesCompilationException(source, message, line, column.map(_ - 1))
            }
            case e => throw e
        }
        
        (generatedDir * "*.scala").get.map(_.getAbsoluteFile)
    }
    
    val ScalaTemplates = (sourceDirectory:File, generatedDir:File) => {
        import play.templates._
        
        (generatedDir ** "template_*").get.map(GeneratedSource(_)).foreach(_.sync())
        try {
            (sourceDirectory ** "*.scala.html").get.foreach { template =>
                ScalaTemplateCompiler.compile(template, sourceDirectory, generatedDir)
            }
        } catch {
            case TemplateCompilationError(source, message, line, column) => {
                throw TemplateCompilationException(source, message, line, column-1)
            }
            case e => throw e
        }

        (generatedDir * "*.scala").get.map(_.getAbsoluteFile)
    }
    
    
    
    // ----- Play prompt
    
    val playPrompt = { state:State =>
        
        val extracted = Project.extract(state)
        import extracted._
        
        (name in currentRef get structure.data).map { name =>
            new ANSIBuffer().append("[").cyan(name).append("] $ ").toString
        }.getOrElse("> ")  
        
    }
    
    
    
    // ----- Reloader
    
    def newReloader(state:State) = {
        
        val extracted = Project.extract(state)
    
        new ReloadableApplication(extracted.currentProject.base) {
        
            
            // ----- Internal state used for reloading is kept here
        
            var currentProducts = Map.empty[java.io.File,Long]
            var currentAnalysis = Option.empty[sbt.inc.Analysis]
        
            def updateAnalysis(newAnalysis:sbt.inc.Analysis) = {
                val classFiles = newAnalysis.stamps.allProducts
                val newProducts = classFiles.map { classFile =>
                    classFile -> classFile.lastModified
                }.toMap
                val updated = if(newProducts != currentProducts) {
                    Some(newProducts)
                } else {
                    None
                }
                updated.foreach(currentProducts = _)
                currentAnalysis = Some(newAnalysis)
            
                updated
            }
        
            def findSource(className:String) = {
                currentAnalysis.flatMap { analysis =>
                    analysis.apis.internal.flatMap {
                        case (sourceFile, source) => {
                            source.api.definitions.find( defined => defined.name == className || (defined.name + "$") == className).map(_ => {
                                sourceFile:java.io.File
                            })
                        }
                    }.headOption
                }
            }
            
            def remapProblemForGeneratedSources(problem:xsbti.Problem) = {
                
                problem.position.sourceFile.collect {
                    
                    // Templates
                    case play.templates.MaybeGeneratedSource(generatedSource) => {
                        new xsbti.Problem {
                            def message = problem.message
                            def position = new xsbti.Position {
                                def line = {
                                    problem.position.line.map(l => generatedSource.mapLine(l.asInstanceOf[Int])).map(l => xsbti.Maybe.just(l.asInstanceOf[java.lang.Integer])).getOrElse(xsbti.Maybe.nothing[java.lang.Integer])
                                }
                                def lineContent = ""
                                def offset = xsbti.Maybe.nothing[java.lang.Integer]
                                def pointer = {
                                    problem.position.offset.map { offset =>
                                        generatedSource.mapPosition(offset.asInstanceOf[Int]) - IO.readLines(generatedSource.source.get).take(problem.position.line.map(l => generatedSource.mapLine(l.asInstanceOf[Int])).get - 1).mkString("\n").size - 1
                                    }.map { p =>
                                        xsbti.Maybe.just(p.asInstanceOf[java.lang.Integer])
                                    }.getOrElse(xsbti.Maybe.nothing[java.lang.Integer])
                                }
                                def pointerSpace = xsbti.Maybe.nothing[String]
                                def sourceFile = xsbti.Maybe.just(generatedSource.source.get)
                                def sourcePath = xsbti.Maybe.just(sourceFile.get.getCanonicalPath)
                            }
                            def severity = problem.severity
                        }
                    }
                    
                    // Routes files
                    case play.core.Router.RoutesCompiler.MaybeGeneratedSource(generatedSource) => {
                        new xsbti.Problem {
                            def message = problem.message
                            def position = new xsbti.Position {
                                def line = {
                                    problem.position.line.flatMap(l => generatedSource.mapLine(l.asInstanceOf[Int])).map(l => xsbti.Maybe.just(l.asInstanceOf[java.lang.Integer])).getOrElse(xsbti.Maybe.nothing[java.lang.Integer])
                                }
                                def lineContent = ""
                                def offset = xsbti.Maybe.nothing[java.lang.Integer]
                                def pointer = xsbti.Maybe.nothing[java.lang.Integer]
                                def pointerSpace = xsbti.Maybe.nothing[String]
                                def sourceFile = xsbti.Maybe.just(new File(generatedSource.source.get.path))
                                def sourcePath = xsbti.Maybe.just(sourceFile.get.getCanonicalPath)
                            }
                            def severity = problem.severity
                        }
                    }
                    
                }.getOrElse {
                    problem
                }
                
            }
            
            def getProblems(incomplete:Incomplete):Seq[xsbti.Problem] = {
                (Compiler.allProblems(incomplete) ++ {
                    Incomplete.linearize(incomplete).filter(i => i.node.isDefined && i.node.get.isInstanceOf[ScopedKey[_]]).flatMap { i =>
                        val JavacError = """\[error\]\s*(.*[.]java):(\d+):\s*(.*)""".r
                        val JavacErrorPosition = """\[error\](\s*)\^\s*""".r
                        
                        Project.evaluateTask(streamsManager, state).get.toEither.right.toOption.map { streamsManager =>
                            Output.lastLines(i.node.get.asInstanceOf[ScopedKey[_]], streamsManager).map(_.replace(scala.Console.RESET, "")).map(_.replace(scala.Console.RED, "")).collect { 
                                case JavacError(file,line,message) => (file,line,message)
                                case JavacErrorPosition(pos) => pos.size  
                            }
                        }.map { errors =>
                            errors.headOption.filter(_.isInstanceOf[Tuple3[_,_,_]]).asInstanceOf[Option[Tuple3[String,String,String]]] -> errors.drop(1).headOption.filter(_.isInstanceOf[Int]).asInstanceOf[Option[Int]]
                        }.collect {
                            case (Some(error), maybePosition) => new xsbti.Problem {
                                def message = error._3
                                def position = new xsbti.Position {
                                    def line = xsbti.Maybe.just(error._2.toInt)
                                    def lineContent = ""
                                    def offset = xsbti.Maybe.nothing[java.lang.Integer]
                                    def pointer = maybePosition.map(pos => xsbti.Maybe.just((pos - 1).asInstanceOf[java.lang.Integer])).getOrElse(xsbti.Maybe.nothing[java.lang.Integer])
                                    def pointerSpace = xsbti.Maybe.nothing[String]
                                    def sourceFile = xsbti.Maybe.just(file(error._1))
                                    def sourcePath = xsbti.Maybe.just(error._1)
                                }
                                def severity = xsbti.Severity.Error
                            }
                        }
                        
                    }
                }).map(remapProblemForGeneratedSources)
            }
        
            def reload = {
                
                PlayProject.synchronized {
                    
                    Project.evaluateTask(playReload, state).get.toEither
                        .left.map { incomplete =>
                            Incomplete.allExceptions(incomplete).headOption.map {
                                case e:PlayException => e 
                                case e:xsbti.CompileFailed => {
                                    getProblems(incomplete).headOption.map(CompilationException(_)).getOrElse {
                                        UnexpectedException(Some("Compilation failed without reporting any problem!?"), Some(e))
                                    }
                                }
                                case e => UnexpectedException(unexpected = Some(e))
                            }.getOrElse(
                                UnexpectedException(Some("Compilation task failed without any exception!?"))
                            )
                        }
                        .right.map { compilationResult =>
                            updateAnalysis(compilationResult).map { _ =>
                                new java.net.URLClassLoader({
                                    Project.evaluateTask(dependencyClasspath in Runtime, state).get.toEither.right.get.map(_.data.toURI.toURL).toArray
                                }, this.getClass.getClassLoader)
                            }
                        }
                    
                }
            
            }
        }
    
    }
    
    
    
    
    // ----- Play commands
    
    val playRunCommand = Command.command("run") { state:State =>
        
        val reloader = newReloader(state)
        
        println()
        
        val server = new play.core.server.NettyServer(reloader)
        
        println()
        println(new ANSIBuffer().green("(Servidor iniciado, use Ctrl+D para parar e voltar para o console...)").toString)
        println()
        
        waitForKey()
        
        server.stop()
        
        println()
        
        state     
    }
    
    val playStartCommand = Command.command("start") { state:State =>
        
        val extracted = Project.extract(state)
        
        Project.evaluateTask(compile in Compile, state).get.toEither match {
            case Left(_) => {
                println()
                println("Não é possível  começar com erros.")
                println()
                state.fail
            }
            case Right(_) => {
                
                Project.evaluateTask(dependencyClasspath in Runtime, state).get.toEither.right.map { dependencies =>
                    
                    val classpath = dependencies.map(_.data).map(_.getCanonicalPath).reduceLeft(_ + java.io.File.pathSeparator + _)
                    
                    import java.lang.{ProcessBuilder => JProcessBuilder}
                    val builder = new JProcessBuilder(Array(
                        "java", "-cp", classpath, "play.core.server.NettyServer", extracted.currentProject.base.getCanonicalPath
                    ) : _*)
                    
                    new Thread {
                        override def run {
                            System.exit(Process(builder) !)
                        }
                    }.start()

                    println(new ANSIBuffer().green(
                        """|
                           |(Starting server. Type Ctrl+D to exit logs, the server will remain in background)
                           |""".stripMargin
                    ).toString)
                    
                    waitForKey()

                    println()

                    state.copy(remainingCommands = Seq.empty)   
                    
                }.right.getOrElse {
                    println()
                    println("Oops, não consegue iniciar o servidor?")
                    println()
                    state.fail
                }
                
            }
        }
        
    }
    
    val playHelpCommand = Command.command("help") { state:State =>

        println(
            """
                |Bem-vindo ao Play 2.0!
                |
                |Estes comandos estão disponíveis:
                |-----------------------------
                |clean          Limpa todos os arquivos gerados.
                |compile        Compila a aplicação atual.
                |dist           Construir um pacote de aplicação standalone.
                |package        Enpacotar sua aplicação como um JAR.
                |publish        Publicar sua aplicação em um repositório remoto.
                |publish-local  Publicar sua aplicação em um repositório local.
                |reload         Recarregar o arquivo de build da aplicação atual.
                |run            Execute a aplicação corrente no modo DEV (Desenvolvimento).
                |start          Inicie a aplicação atual em outra JVM no modo PROD.
                |update         Atualizar as dependências da aplicação.
                |
                |Você também pode ver a documentação completa em """.stripMargin +
                new ANSIBuffer().underscore("http://www.playframework.org").append(".\n")
        )
        
        state
    }
    
    val playCommand = Command.command("play") { state:State =>
        
        val extracted = Project.extract(state)
        import extracted._

        // Display logo
        println(play.console.Console.logo)
        println("""
            |> Digite "help" ou "license" para mais informações.
            |> Digite "exit" ou use Ctrl+D para deixar este ambiente.
            |""".stripMargin 
        )

        state.copy(
            remainingCommands = state.remainingCommands :+ "shell"
        )
        
    }
    
    
    
    
    // ----- Default settings
    
    lazy val defaultSettings = Seq[Setting[_]](
        
        target <<= baseDirectory / "target",

        sourceDirectory in Compile <<= baseDirectory / "app",
        
        confDirectory <<= baseDirectory / "conf",

        scalaSource in Compile <<= baseDirectory / "app",

        javaSource in Compile <<= baseDirectory / "app",

        distDirectory <<= baseDirectory / "dist",

        libraryDependencies += "play" %% "play" % play.core.PlayVersion.current,

        sourceGenerators in Compile <+= (confDirectory, sourceManaged in Compile) map RouteFiles,
        
        sourceGenerators in Compile <+= (sourceDirectory in Compile, sourceManaged in Compile) map ScalaTemplates,

        commands ++= Seq(playCommand, playRunCommand, playStartCommand, playHelpCommand),

        shellPrompt := playPrompt,

        copyResources in Compile <<= (copyResources in Compile, playCopyResources) map { (r,pr) => r ++ pr },

        mainClass in (Compile, run) := Some(classOf[play.core.server.NettyServer].getName),

        dist <<= distTask,

        playCopyResources <<= playCopyResourcesTask,

        playCompileEverything <<= playCompileEverythingTask,

        playPackageEverything <<= playPackageEverythingTask,

        playReload <<= playReloadTask,

        cleanFiles <+= distDirectory.identity,
        
        playResourceDirectories := Seq.empty[File],
        
        playResourceDirectories <+= baseDirectory / "conf",
        
        playResourceDirectories <+= baseDirectory / "public"
        
    )

    
    
    // ----- Create a Play project with default settings
    
    def apply(name:String, applicationVersion:String = "0.1", dependencies:Seq[ModuleID] = Nil, path:File = file(".")) = {
            
        Project(name, path)
            .settings( PlayProject.defaultSettings : _*)
            .settings(
        
                version := applicationVersion,

                libraryDependencies ++= dependencies,

                resolvers ++= Option(System.getProperty("play.home")).map { home =>
                    Resolver.file("play-repository", file(home) / "../repository")
                }.toSeq
            
            )
        
    }
    
}
