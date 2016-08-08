package au.csiro.variantspark.cli

import au.csiro.sparkle.common.args4j.ArgsApp
import au.csiro.sparkle.cmd.CmdApp
import org.kohsuke.args4j.Option
import au.csiro.pbdava.ssparkle.common.arg4j.AppRunner
import au.csiro.pbdava.ssparkle.spark.SparkApp
import collection.JavaConverters._
import au.csiro.variantspark.input.VCFSource
import au.csiro.variantspark.input.VCFFeatureSource
import au.csiro.variantspark.input.HashingLabelSource
import au.csiro.variantspark.algo.WideRandomForest
import org.apache.spark.mllib.linalg.Vectors
import au.csiro.variantspark.input.CsvLabelSource
import au.csiro.variantspark.algo.cmd.Echoable
import org.apache.spark.Logging
import org.apache.commons.lang3.builder.ToStringBuilder
import au.csiro.variantspark.algo.cmd.EchoUtils._
import au.csiro.pbdava.ssparkle.common.utils.LoanUtils
import com.github.tototoshi.csv.CSVWriter
import au.csiro.pbdava.ssparkle.common.arg4j.TestArgs
import org.apache.hadoop.fs.FileSystem

class ImportanceCmd extends ArgsApp with SparkApp with Echoable with Logging with TestArgs {

  @Option(name="-if", required=true, usage="This is input files", aliases=Array("--input-file"))
  val inputFile:String = null
  
  @Option(name="-ff", required=true, usage="Features file", aliases=Array("--feature-file"))
  val featuresFile:String = null

  @Option(name="-fc", required=true, usage="Feature column", aliases=Array("--feature-column"))
  val featureColumn:String = null
  
  @Option(name="-nv", required=false, usage="Number od variables to print", aliases=Array("--n-variables"))
  val nVariables:Int = 20

  @Option(name="-t", required=false, usage="Number of tree to build", aliases=Array("--n-trees") )
  val nTrees:Int = 5

  @Option(name="-of", required=false, usage="Output file", aliases=Array("--output-file") )
  val outputFile:String = null

  @Override
  def testArgs = Array("-if", "data/small.vcf", "-ff", "data/small-labels.csv", "-fc", "22_16051249")
  
  @Override
  def run():Unit = {
    implicit val fs = FileSystem.get(sc.hadoopConfiguration)  
    logDebug(s"Runing with filesystem: ${fs}, home: ${fs.getHomeDirectory}")
    logInfo("Running with params: " + ToStringBuilder.reflectionToString(this))
    echo(s"Finding  ${nVariables}  most important features using random forest")

    echo(s"Loading header from: ${inputFile}")
    val vcfSource = VCFSource(sc.textFile(inputFile))
    verbose(s"VCF Version: ${vcfSource.version}")
    verbose(s"VCF Header: ${vcfSource.header}")    
    val source  = VCFFeatureSource(vcfSource)
    echo(s"Loaded rows: ${dumpList(source.rowNames)}")
     
    echo(s"Loading labels from: ${featuresFile}, column: ${featureColumn}")
    val labelSource = new CsvLabelSource(featuresFile, featureColumn)
    val labels = labelSource.getLabels(source.rowNames)
    echo(s"Loaded labels: ${dumpList(labels.toList)}")
    
    echo(s"Loading features from: ${inputFile}")
    
    val inputData = source.features().map(_.toVector).zipWithIndex().cache()
    val totalVariables = inputData.count()
    val variablePerview = inputData.map({case (f,i) => f.label}).take(defaultPreviewSize).toList
    
    echo(s"Loaded variables: ${dumpListHead(variablePerview, totalVariables)}")    

    if (isVerbose) {
      verbose("Data preview:")
      source.features().take(defaultPreviewSize).foreach(f=> verbose(s"${f.label}:${dumpList(f.values.toList, longPreviewSize)}"))
    }
    
    echo(s"Training random forest - trees: ${nTrees}")  
    val rf = new WideRandomForest()
    val traningData = inputData.map{ case (f, i) => (f.values, i)}
    val result  = rf.run(traningData, labels, nTrees)  
    
    echo(s"Random forest oob accuracy: ${result.oobError}") 
    // build index for names
    val index = inputData.map({case (f,i) => (i, f.label)}).collectAsMap()
    val varImportance = result.variableImportance.toSeq.sortBy(-_._2).take(nVariables).map({ case (i, importance) => (index(i), importance)})
    
    if (isEcho && outputFile!=null) {
      echo("Variable importance preview")
      varImportance.take(math.min(nVariables, defaultPreviewSize)).foreach({case(label, importance) => echo(s"${label}: ${importance}")})
    }
    
    LoanUtils.withCloseable(if (outputFile != null ) CSVWriter.open(outputFile) else CSVWriter.open(System.out)) { writer =>
      writer.writeRow(List("variable","importance"))
      writer.writeAll(varImportance.map(t => t.productIterator.toSeq))
    }
  }
}

object ImportanceCmd  {
  def main(args:Array[String]) {
    AppRunner.mains[ImportanceCmd](args)
  }
}