package cn.datacraft.cspsim;

import cn.datacraft.cspsim.CspTypes.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class CspRulesTest {
    @Test void traversalAndWindowsReservedNamesAreRejected(){
        for(String path:List.of("/a/../b","/a/./b","/a//b","/a\u0000b"))assertThatThrownBy(()->CspFiles.path(path,false)).isInstanceOf(IllegalArgumentException.class);
        for(String path:List.of("D:/con.cpp","D:/test.","D:/test ","D:/a:b"))assertThatThrownBy(()->CspFiles.path(path,true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(CspFiles.path("d:\\Exam\\Sum.cpp",true)).isEqualTo("/D/Exam/Sum.cpp");
    }
    @Test void movingAnEntireFolderPreservesFilesAndPreventsSelfDescendants(){
        Map<String,Entry> entries=new LinkedHashMap<>();CspFiles.seed(entries,"/D/A/B");entries.put("/D/A/B/file.cpp",new Entry(false));
        assertThatThrownBy(()->CspFiles.operate(entries,"move","/D/A","/d/a/b/new",true)).hasMessageContaining("自己的子目录");
        CspFiles.operate(entries,"move","/D/A","/D/a",true);assertThat(entries).containsKeys("/D/a/B/file.cpp").doesNotContainKey("/D/A");
        CspFiles.operate(entries,"delete","/D/a",null,true);assertThat(entries).containsOnlyKeys("/","/D");
    }
    @Test void subtaskIsScoredOnlyWhenEveryCaseInTheGroupPasses(){
        Problem p=new Problem();p.scoring="SUBTASKS";
        for(int i=0;i<3;i++){TestCase c=new TestCase();c.id=""+i;c.subtask=i<2?"A":"B";c.score=new BigDecimal(i<2?"60":"40");p.cases.add(c);}
        Result result=new Result();result.verdict="PARTIAL";
        for(int i=0;i<3;i++){CaseResult r=new CaseResult();r.id=""+i;r.verdict=i==1?"WA":"AC";result.cases.add(r);}
        assertThat(CspSimService.score(p,result)).isEqualByComparingTo("40");
        p.scoring="POINTS";assertThat(CspSimService.score(p,result)).isEqualByComparingTo("100");
        result.cases.remove(0);assertThatThrownBy(()->CspSimService.score(p,result)).hasMessageContaining("不完整");
    }
    @Test void csvRoundTripsQuotesAndGuardsSpreadsheetFormulas(){
        assertThat(CspFiles.csv("姓名,班级,学号\n\"张\"\"三\",\"一\n班\",1\n".getBytes())).containsExactly(List.of("姓名","班级","学号"),List.of("张\"三","一\n班","1"));
        assertThat(CspFiles.csvCell("=1+2")).isEqualTo("\"'=1+2\"");
    }
}
