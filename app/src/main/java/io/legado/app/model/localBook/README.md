# 书籍文件导入解析

* BaseLocalBookParse.kt 本地书籍解析接口
* LocalBook.kt 导入解析总入口
* TextFile.kt 解析txt
* MarkdownFile.kt 解析 Markdown，按标题生成目录并适配正文与本地图片
* EpubFile.kt 解析epub，负责章节拆分、图片地址解析与注解处理入口
* EpubHrefResolver.kt EPUB 2/3 统一内部链接解析（相对路径、百分号编码、Unicode/中文片段、查询串）
* EpubFootnoteProcessor.kt 将 EPUB 脚注/尾注/批注结构转换为可弹出注解
* EpubFootnoteLink.kt 注解在正文缓存与排版层之间的传输格式（纯文本 + 富文本 HTML）
* EpubContentCache.kt EPUB 正文缓存版本头，升级后旧缓存自动重新解析
* PdfFile.kt 解析pdf 纯图片形式
* UmdFile.kt 解析umd
