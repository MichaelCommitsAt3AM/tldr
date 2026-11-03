"""Regenerate the reusable PDF: python tools/generate_design_guide.py."""
from pathlib import Path
from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, PageBreak, Table, TableStyle, Flowable

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'Minimal-Utility-Design-Guide.pdf'
FONT_DIR = Path('C:/Windows/Fonts')
if (FONT_DIR / 'segoeui.ttf').exists():
    pdfmetrics.registerFont(TTFont('UI', str(FONT_DIR / 'segoeui.ttf')))
    pdfmetrics.registerFont(TTFont('UI-Medium', str(FONT_DIR / 'segoeuib.ttf')))
else:
    pdfmetrics.registerFontFamily('Helvetica', normal='Helvetica', bold='Helvetica-Bold')
FONT = 'UI' if 'UI' in pdfmetrics.getRegisteredFontNames() else 'Helvetica'
BOLD = 'UI-Medium' if FONT == 'UI' else 'Helvetica-Bold'
pdfmetrics.registerFontFamily(FONT, normal=FONT, bold=BOLD)
INK, MUTED, RULE, WASH = [colors.HexColor(x) for x in ('#252525', '#696969', '#DEDEDE', '#F0F0F0')]
styles = {
    'title': ParagraphStyle('title', fontName=BOLD, fontSize=29, leading=36, textColor=INK, spaceAfter=16),
    'deck': ParagraphStyle('deck', fontName=FONT, fontSize=12, leading=19, textColor=MUTED, spaceAfter=18),
    'h1': ParagraphStyle('h1', fontName=BOLD, fontSize=22, leading=29, textColor=INK, spaceAfter=16),
    'h2': ParagraphStyle('h2', fontName=BOLD, fontSize=12, leading=18, textColor=INK, spaceBefore=10, spaceAfter=6),
    'body': ParagraphStyle('body', fontName=FONT, fontSize=10, leading=16, textColor=INK, spaceAfter=8),
    'small': ParagraphStyle('small', fontName=FONT, fontSize=8.5, leading=13, textColor=MUTED, spaceAfter=6),
    'table': ParagraphStyle('table', fontName=FONT, fontSize=9, leading=14, textColor=INK),
    'label': ParagraphStyle('label', fontName=BOLD, fontSize=9, leading=14, textColor=MUTED, spaceAfter=10),
    'prompt': ParagraphStyle('prompt', fontName=FONT, fontSize=9.5, leading=15, textColor=INK, spaceAfter=9),
}
story = []
def p(text, style='body'): return Paragraph(text, styles[style])
def add(text, style='body'): story.append(p(text, style))
def section(title, text): add(title, 'h2'); add(text)
def bullets(items):
    for item in items: add('- ' + item)
def page(number, title, deck):
    if story: story.append(PageBreak())
    add(f'{number:02d} / DESIGN SYSTEM', 'label'); add(title, 'h1'); add(deck, 'deck')
def table(rows, widths):
    cells = [[p(str(c), 'table') for c in row] for row in rows]
    t = Table(cells, colWidths=widths, hAlign='LEFT', repeatRows=1)
    t.setStyle(TableStyle([
        ('VALIGN', (0,0), (-1,-1), 'TOP'), ('BACKGROUND',(0,0),(-1,0),WASH),
        ('LINEBELOW',(0,0),(-1,0),.6,RULE), ('LINEBELOW',(0,1),(-1,-1),.4,RULE),
        ('LEFTPADDING',(0,0),(-1,-1),9), ('RIGHTPADDING',(0,0),(-1,-1),9),
        ('TOPPADDING',(0,0),(-1,-1),8), ('BOTTOMPADDING',(0,0),(-1,-1),8),
    ])); story.append(t)

class Wireframes(Flowable):
    """Original diagrams of the design pattern, rather than product screenshots."""
    def __init__(self): super().__init__(); self.width=475; self.height=222
    def draw(self):
        c=self.canv
        def text(x,y,t,size=9,bold=False,color=INK):
            c.setFont(BOLD if bold else FONT,size);c.setFillColor(color);c.drawString(x,y,t)
        for x,title in [(0,'Create'),(166,'Read'),(332,'Companion')]:
            c.setFillColor(colors.HexColor('#FAFAFA'));c.setStrokeColor(RULE);c.roundRect(x,18,146,190,8,stroke=1,fill=1)
            text(x+12,188,'APP',9,True);c.line(x,176,x+146,176)
            text(x+12,154,title,13,True)
        c.setStrokeColor(RULE);c.roundRect(12,108,122,28,4,stroke=1,fill=0);text(20,118,'Paste a link',8,color=MUTED)
        c.setFillColor(INK);c.roundRect(12,69,122,27,4,stroke=0,fill=1);text(46,79,'Create',9,color=colors.white)
        text(42,47,'Upload file',8,color=MUTED)
        text(178,131,'Summary   Check   More',7,color=MUTED)
        for y,w in [(111,116),(98,105),(85,112),(60,108),(47,90)]:
            c.setStrokeColor(RULE);c.line(178,y,178+w,y)
        c.setFillColor(colors.white);c.setStrokeColor(RULE);c.roundRect(342,43,126,91,6,stroke=1,fill=1)
        text(352,116,'Summary     Check',8,True);text(352,96,'Claim to inspect',9);text(352,80,'Verdict / sources',8,color=MUTED);text(352,58,'Open full report',8)
        text(0,0,'Pattern illustrations. Adapt names and content to your project.',8,color=MUTED)

page(1, 'Minimal utility\ndesign guide', 'A reusable visual and interaction system for small, professional apps. Developed from the approved TL;DR Utility direction.')
add('Quiet by default. Useful at every step.', 'h2')
add('Use neutral surfaces, readable text, predictable controls, and restrained spacing. Let the primary task and the user\'s content carry the interface. Polish comes from alignment, hierarchy, and consistent behavior.')
story.append(Spacer(1,12));story.append(Wireframes())
section('The core rules', 'One clear primary action per view. Give history its own destination. Keep reading content directly on the page. Reveal supporting details when requested. Use short, literal labels.')
section('The intended character', 'Minimal, bland in a deliberate way, almost professional: charcoal, off-white, gray, system typography, small radii, flat surfaces. Avoid decorative gradients, bright branding, oversized slogans, unnecessary cards, and animation that competes with the task.')
add('Use this guide as a design brief, a build prompt, and an acceptance checklist for future hobby apps. The patterns are portable; the example labels are not mandatory.', 'small')

page(2, 'Visual foundations', 'Define tokens once and use them across screens, dialogs, notifications, and floating surfaces.')
table([
    ['Token','Light','Dark','Use'],
    ['Background','#FAFAFA','#191919','Main page and dialog surface'],
    ['Text','#252525','#E8E8E8','Titles and primary content'],
    ['Secondary text','#696969','#ABABAB','Metadata and helper text'],
    ['Subtle fill','#F0F0F0','#282828','Selection and grouped controls'],
    ['Divider','#DEDEDE','#3C3C3C','Quiet structural separators'],
    ['Primary action','#303030','#DEDEDE','One high-emphasis action'],
    ['On primary','#FFFFFF','#202020','Primary-action text'],
], [108,75,75,217])
section('Type', 'Use the platform system sans-serif. Use regular for body text and medium for headings and selected controls. Recommended mobile sizes: screen title 24sp / 31sp line height; section heading 16sp / 24sp; reading text 16sp / 27sp; ordinary text 14sp / 22sp; metadata 12sp / 19sp. Scale with user font preferences.')
section('Space and shape', 'Use a small spacing scale: 4, 8, 12, 16, 24, 32. Default mobile content inset: 22-24dp. Controls: 8dp radius. Compact popup: 12dp radius. Dialog: up to 16dp. Use a 1dp divider. Keep main screens flat; a subtle shadow is appropriate only for an actual floating surface.')
section('Color has a job', 'Use neutral selection, underlines, labels, and icons before introducing color. Muted red may identify an actionable error. Never rely on color alone for status or verdicts. Avoid dynamic wallpaper colors when they would override this palette.')

page(3, 'Screen and navigation patterns', 'Organize the app around the user\'s tasks, with a stable home for every type of content.')
table([
 ['Surface','Recommended structure'],
 ['Home / create','Modest heading, input with integrated paste, one primary action, secondary upload or import. No duplicate history list.'],
 ['Library / history','Search, readable text rows, title, source or type, date and status. Use dividers instead of repeated card containers.'],
 ['Detail / reading','Title and metadata, compact local navigation, one concise summary and copy/share/original icons. Keep reading content on the page.'],
 ['Settings','Grouped rows with concise labels. Segmented choices for short exclusive options. Expand advanced connection details only when needed.'],
 ['Setup','Explain what is needed, then show the fields and one next action. Keep credentials and implementation details out of ordinary task flows.'],
 ], [103,372])
section('Global versus local navigation', 'Use two to four global destinations when the app needs them; for TL;DR, Home and Library are enough. Put Settings in the header. Put detail-specific modes inside the detail screen. Preserve each view\'s state and make Back return to the destination that opened it.')
section('Detail modes', 'Lead with the main content. Order supporting tools by likely use. TL;DR uses only Summary and Check. Put View transcript in the header menu and questions in a bottom composer. The Check view uses compact claim rows; tapping opens a detail sheet with the verdict, explanation, and sources. Expand source excerpts on demand. Ask about this claim transfers its context to the composer. A different app should name these modes for its own tasks rather than copying the labels mechanically.')
section('Forms and actions', 'Keep labels visible after entry. Put paste beside the input it affects. Disable duplicate submissions while busy and explain progress in place. Prefer explicit verbs: Create, Summarize, Upload file, Retry, Copy, Share. Use smaller text actions for secondary choices.')
section('Responsive adaptation', 'On a wider screen, constrain reading width to about 60-75 characters and increase the outer whitespace. Keep forms comfortably narrow. On a narrow phone, allow text and metadata to wrap; do not solve layout pressure by shrinking essential text.')

page(4, 'Floating companion', 'A compact continuation of the task, with the same visual language and data as the main app.')
table([
 ['State','What the user sees and can do'],
 ['Input','Small header, minimize and close, link field with paste, one primary action.'],
 ['Processing','Short status, indeterminate progress, cancel when supported. Minimize without cancelling the server job.'],
 ['Result','Summary and Check views. One quick summary, copy/share/original icons, Open summary, New summary.'],
 ['Checking','Status and progress inside Check. Keep the shortcut usable while work runs. Preserve the result when minimized.'],
 ['Claim report','Individual claims with textual verdicts and source counts. Expand a claim for its explanation and sources. Open the full report in the app.'],
 ['Failure','Specific error with a recovery action. Preserve the input and any result already obtained.'],
 ], [94,381])
section('Evidence stays inspectable', 'Do not give a whole video a single truth score. Attach a verdict to each claim. Keep the claim text, explanation, source title, domain, and relevant excerpt together. Show when the check ran. Distinguish unchecked, insufficient evidence, and an actual contradictory finding.')
section('Bounded, not cramped', 'Use roughly 320-360dp width when the display allows it, with safe margins. Keep the header visible and bound the body height; scroll long content. Clamp the popup inside the usable display. Make minimize and close separate, labeled actions. Support drag-to-dismiss without making controls hard to tap.')
section('State and handoff', 'Preserve the current tab, report, and expanded claims through minimize/reopen. Open full report should navigate to that same job and Check tab. Source links open their original pages. Sharing invokes the platform share sheet. A new task should not overwrite an in-flight check.')
add('The popup should answer a short question or support a short action. Use the full app for long transcripts and extensive investigation.', 'small')

page(5, 'Behavior and quality', 'Minimal appearance still needs complete states, accessible controls, and dependable feedback.')
section('Required states for every data view', 'Design the first-use, loading, populated, empty-search, offline, failed, retrying, cancelled, and completed states. Use brief text near the action it explains. Preserve content while refreshing. Do not present connection failures as an empty library.')
section('Accessibility defaults', 'Aim for at least 48dp touch targets on Android (44 CSS pixels on the web). Use platform semantics and accessible names for icons. Keep normal text contrast at least 4.5:1 and large text at least 3:1. Check interactive outlines against their background. Do not use subtle dividers as the only indicator of an input boundary.')
section('Text and input', 'Support increased font size, selection and copying of reading content, visible focus, keyboard navigation where relevant, and screen-reader state announcements. Let source titles and errors wrap. Keep the active field visible when the keyboard opens.')
section('Async work', 'Show the current stage instead of a fabricated percentage. Prevent duplicate requests. Keep responses bound to the job that requested them. Recover interrupted polling without starting a duplicate job. Reuse stored results and return the user to the same item after a handoff.')
section('Destructive actions', 'Keep delete separate from the main reading actions. Confirm irreversible removal in plain language, with a clear Keep or Cancel action. Explain what is removed. Make clearing history distinct from cancelling active work.')
section('Motion and ornament', 'Use only brief, functional state transitions. Respect reduced-motion preferences. Avoid pulsing bubbles, looping decoration, large illustrations, and decorative status badges. A simple interface can remain warm through clear wording and thoughtful spacing.')
section('Check the finished build', 'Review narrow and wide layouts, light and dark appearance, enlarged text, long titles and source names, open keyboard, empty data, network failure, and running background work. Review the actual implementation, not only the initial mockup.')

page(6, 'Reusable build brief', 'Paste this into the start of a new project, then supply the app\'s actual tasks and platform.')
prompt = [
 'Design and implement [APP NAME], a [PLATFORM] app for [PRIMARY TASK]. Use a restrained professional utility style: neutral off-white and charcoal surfaces, gray dividers, system sans-serif, regular and medium weights, small consistent corner radii, and flat layouts.',
 'Use these light/dark token pairs: background #FAFAFA/#191919; text #252525/#E8E8E8; secondary text #696969/#ABABAB; subtle fill #F0F0F0/#282828; divider #DEDEDE/#3C3C3C; primary action #303030/#DEDEDE; on-primary #FFFFFF/#202020. Reserve muted color for meaningful errors. Do not use decorative gradients or bright accent colors.',
 'Keep the main task screen focused on inputs and one primary action. Put searchable saved items in a separate Library. Use text rows and dividers instead of repeated cards. Detail pages should read like documents, with supporting tools in compact local tabs. Put Settings in the app header and group preferences into simple rows.',
 'Use about 24dp content insets, a 4/8/12/16/24/32 spacing scale, 8dp control radii, and 48dp Android touch targets. Use modest 24sp screen titles, 16sp reading text, and 12sp metadata, with comfortable line height and scalable type. Adapt these units to the target platform.',
 'If a floating companion is needed, include input, processing, result, and error states. Use a compact bounded panel with separate minimize and close controls. Preserve results when minimized. Reveal details progressively and offer a handoff to the same item in the full app.',
 'Implement loading, empty, search-empty, error, retry, cancellation, success, and destructive-action confirmation states. Preserve inputs on failure. Use accessible labels, visible focus, sufficient contrast, and layouts that survive large text and the keyboard. Use realistic content and verify the build.',
 'App-specific requirements: [DESTINATIONS], [CREATE FLOW], [ITEM DETAILS], [SECONDARY TOOLS], [PERSISTENCE], [API], [OFFLINE BEHAVIOR]. Keep wording literal and concise. Preserve existing functionality when redesigning an existing app.'
]
for text in prompt: add(text, 'prompt')
section('Acceptance checklist', 'One clear primary action per view; history only in Library; no decorative color or oversized slogan; consistent tokens and spacing; accessible controls; complete failure and empty states; readable long content; preserved state; working build; visual review in both themes.')
add('Version 1.1 / 3 November 2026 / Personal design reference. This guide defines a preferred style, not a dependency on a specific UI framework.', 'small')

def chrome(canvas, doc):
    w,h=A4;canvas.saveState();canvas.setStrokeColor(RULE);canvas.setLineWidth(.5);canvas.line(60,43,w-60,43)
    canvas.setFont(FONT,8);canvas.setFillColor(MUTED);canvas.drawString(60,29,'MINIMAL UTILITY / DESIGN REFERENCE');canvas.drawRightString(w-60,29,str(doc.page));canvas.restoreState()

doc=SimpleDocTemplate(str(OUT),pagesize=A4,rightMargin=60,leftMargin=60,topMargin=48,bottomMargin=60,
    title='Minimal Utility Design Guide',author='Personal design reference',subject='Reusable neutral professional app design system')
doc.build(story,onFirstPage=chrome,onLaterPages=chrome)
print(OUT)
