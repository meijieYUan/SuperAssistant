import os

filepath = r"D:\yuanmeijie\Java\SuperAssistant\app\src\main\resources\skills\research_writing_skill\SKILL.md"

# Read the body (after second ---)
with open(filepath, "r", encoding="utf-8-sig") as f:
    content = f.read()
parts = content.split("---", 2)
body = parts[2] if len(parts) > 2 else ""

# Build description as a single line
desc = (
    "科研课题调研与文献综述撰写。当用户需要对某个研究方向或课题进行论文调研、文献检索、"
    "撰写调研报告/综述文档时触发。支持从国内外主流学术网站检索近年论文，"
    "阅读并总结论文的核心意图、方法框架、关键创新点和训练目标，"
    "最后按模板生成结构化的课题调研文档。"
)

new_content = '---\nname: research-writing\ndescription: "' + desc + '"\n---\n' + body

with open(filepath, "w", encoding="utf-8", newline="") as f:
    f.write(new_content)

# Verify
with open(filepath, "r", encoding="utf-8") as f:
    lines = f.readlines()
print(f"Lines: {len(lines)}")
print(f"Line 1: {lines[0].strip()}")
print(f"Line 2: {lines[1].strip()}")
desc_line = lines[2].strip()
print(f"Line 3 length: {len(desc_line)}")
print(f"Line 3 start: {desc_line[:60]}")
print(f"Line 3 end: ...{desc_line[-30:]}")
print(f"Line 4: {lines[3].strip()}")