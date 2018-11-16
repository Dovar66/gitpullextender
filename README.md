# gitpullextender
IntelliJ\AndroidStudio的Git插件

Git Pull项目下所有仓库的当前分支

<ul>
    <li>1.1:
         增加选择面板</li>
    <li>1.0:
         Git Pull项目下所有仓库的当前分支</li>
</ul>

# IntelliJ插件开发的简要流程

1.安装IntelliJ IDEA并启用Gradle插件

2.创建Plugin项目

选择Gradle并勾选IntelliJ Plateform Plugin，然后跟随引导完成项目的创建。创建后的项目目录应该是下图这样的

3.配置项目

接下来我们主要关注两个文件，就是上图中的build.gradle和plugin.xml。

build.gradle此时的内容如下图：

plugin.xml此时的内容如下图：

配置后的内容如下两图：

4.编写Action，继承自AnAction并实现actionPerformed()方法

5.生成插件

运行runIde或者buildPlugin任务后会在build/libs目录下生产插件的jar包

然后将jar包安装到IntelliJ或者AndroidStudio就能用了

6.如何使用


使用Gradle开发只是插件开发方式中的一种，另一种方式则是使用DevKit开发，具体请点击下方地址查看官方文档

IntelliJ插件开发的官方文档地址：点击跳转

